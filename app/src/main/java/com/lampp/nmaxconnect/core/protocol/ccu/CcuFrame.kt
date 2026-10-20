package com.lampp.nmaxconnect.core.protocol.ccu

import java.util.UUID

/**
 * Transport framing for the CCU link (Link B). Pure Kotlin, no Android types; everything is big-endian.
 * Spec: docs/bike-protocol.md §6 — from Yamaha's motoconsdk in Y-Connect 3.9.0.
 */
object CcuUuids {
    val SPP_YAMAHA: UUID = UUID.fromString("00008911-0000-1000-8000-00805f9b34fb")
    val SPP_GENERIC: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    val SERVICE: UUID = UUID.fromString("AFA2CDF4-ECCF-46A7-A5EA-9DA428C0157A")
    val CONTROL_WRITE: UUID = UUID.fromString("B606C7F9-E5A1-4E75-B313-2A920054A8EB")
    val CONTROL_NOTIFY: UUID = UUID.fromString("9C810D26-B605-4306-8C1C-755A1BA3066C")
    val DDT_OUT_STATUS: UUID = UUID.fromString("75AB2788-87F6-4F61-BA9C-6F85A12600D1")
    val DDT_OUT_HEADER: UUID = UUID.fromString("56851826-A090-4F6A-A5DF-D1776520F74A")
    val DDT_OUT_DATA: UUID = UUID.fromString("E998AEAB-EA8F-4063-83AA-2017C1A44484")
    val DDT_IN_ACK: UUID = UUID.fromString("1A1A7A79-3D72-42A1-B084-843058453068")
    val DDT_IN_HEADER: UUID = UUID.fromString("77808451-E42F-4F6B-83ED-3AB44AA3D16A")
    val DDT_IN_DATA: UUID = UUID.fromString("347456EE-A588-4DD5-BD98-62BE3672E27A")
    val DDT_V2_NOTIFY: UUID = UUID.fromString("E974B9A1-FA31-43C6-91AE-09931F3F6EF1")
    val INDICATE_A: UUID = UUID.fromString("56DC2D58-CBE1-4BA9-A104-E9B8EB4DA95C")
    val INDICATE_B: UUID = UUID.fromString("AB8D233A-CC58-4932-B397-A3751911E021")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Subscribed in this order, one GATT op at a time, after requestMtu(512). */
    val NOTIFY = listOf(CONTROL_NOTIFY, DDT_OUT_STATUS, DDT_IN_HEADER, DDT_IN_DATA, DDT_V2_NOTIFY)
    val INDICATE = listOf(INDICATE_A, INDICATE_B)

    /** Y-Connect picks the RFCOMM UUID from the bike's classic name; null means the name is unusable. */
    fun sppFor(deviceName: String?): UUID? = when {
        deviceName == null || deviceName.length < 16 -> null
        deviceName.substring(9, 16) == "11111BB" -> SPP_GENERIC
        else -> SPP_YAMAHA
    }

    const val CCU_ID_LENGTH = 14

    /** CCU id from an LE name `YCCU_`/`YSCCU_` + 14 chars, as DeviceIdentificationUtils.getCcuid; else null. */
    fun ccuIdFromBleName(name: String?): String? = name
        ?.takeIf { it.length >= 19 && (it.startsWith("YCCU_") || it.startsWith("YSCCU_")) }
        ?.takeLast(CCU_ID_LENGTH)
}

/** SPP bootstrap frame: `01 | len u32 | payload | 04`. */
object CcuSpp {
    const val START: Byte = 0x01
    const val END: Byte = 0x04

    /** Bootstrap replies are tiny; a larger length means we locked onto garbage. */
    const val MAX_PAYLOAD = 4096

    fun encode(payload: ByteArray): ByteArray = byteArrayOf(START) + u32(payload.size) + payload + byteArrayOf(END)
}

/**
 * Turns the SPP byte stream into payloads. Handles split reads and several frames per read; on a bad
 * length or missing end byte it skips one byte and re-syncs on the next 0x01. Feed it from one reader.
 */
class CcuSppDecoder {
    private var buf = ByteArray(256)
    private var size = 0

    /** Frames dropped for a bad length or end byte since creation. */
    var rejected = 0
        private set

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<ByteArray> {
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        bytes.copyInto(buf, size, 0, length)
        size += length

        val frames = mutableListOf<ByteArray>()
        var start = 0
        while (true) {
            while (start < size && buf[start] != CcuSpp.START) start++
            if (size - start < HEADER) break
            val len = readU32(buf, start + 1)
            if (len !in 0..CcuSpp.MAX_PAYLOAD) { rejected++; start++; continue }
            val end = start + HEADER + len + 1
            if (end > size) break
            if (buf[end - 1] != CcuSpp.END) { rejected++; start++; continue }
            frames += buf.copyOfRange(start + HEADER, end - 1)
            start = end
        }
        buf.copyInto(buf, 0, start, size)
        size -= start
        return frames
    }

    private companion object {
        const val HEADER = 5
    }
}

/**
 * DDT-out (phone → CCU bulk) pieces. The session drives the status handshake (docs/bike-protocol.md §6.3);
 * this only builds the bytes.
 */
object CcuDdtOut {
    /** Y-Connect caps chunks at 180 bytes even with MTU 512 (McClient.useV1MaxPacketSize = false). */
    const val MAX_PACKET = 180
    private const val CHUNK_HEADER = 4

    fun header(requestId: Int, payloadSize: Int): ByteArray = byteArrayOf(requestId.toByte()) + u24(payloadSize)

    /** Data bytes per write for an ATT [mtu]: min(MTU-3, 180) minus the 4-byte chunk header. */
    fun chunkSize(mtu: Int): Int = minOf(mtu - 3, MAX_PACKET) - CHUNK_HEADER

    fun chunk(requestId: Int, payload: ByteArray, offset: Int, mtu: Int): ByteArray {
        val end = minOf(offset + chunkSize(mtu), payload.size)
        return byteArrayOf(requestId.toByte()) + u24(offset) + payload.copyOfRange(offset, end)
    }

    /** Status `[01 reqId]`: the CCU accepted our header and wants data. */
    fun isActive(status: ByteArray, requestId: Int) = status.size >= 2 && status[0].toInt() == 1 && status[1] == requestId.toByte()

    /** Status `[00 ..]`: idle; `[00 00]` after the last chunk means delivered. */
    fun isIdle(status: ByteArray) = status.size >= 2 && status[0].toInt() == 0
}

/** Rolling request id 1..255 (0 is never used). Not thread-safe; owned by one session. */
class CcuRequestIds(private var last: Int = 0) {
    fun next(): Int {
        last = if (last >= 255) 1 else last + 1
        return last
    }
}

internal fun u16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
internal fun u24(v: Int) = byteArrayOf((v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
internal fun u32(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
internal fun u64(v: Long) = ByteArray(8) { (v shr (56 - 8 * it)).toByte() }

internal fun readU16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
internal fun readI16(b: ByteArray, at: Int) = readU16(b, at).toShort().toInt()
internal fun readU32(b: ByteArray, at: Int) = (readU16(b, at) shl 16) or readU16(b, at + 2)

/** Big-endian unsigned value of [len] bytes from [at]; fits ODO (≤ 7 bytes). */
internal fun readUnsigned(b: ByteArray, at: Int = 0, len: Int = b.size - at): Long {
    var v = 0L
    for (i in at until at + len) v = (v shl 8) or (b[i].toLong() and 0xFF)
    return v
}
