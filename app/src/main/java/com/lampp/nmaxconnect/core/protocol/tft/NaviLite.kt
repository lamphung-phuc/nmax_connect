package com.lampp.nmaxconnect.core.protocol.tft

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer

/**
 * Framing for the TFT "NaviLite" link (RFCOMM 0x7220). Pure Kotlin, no Android types.
 * Spec: docs/bike-protocol.md §2 — verified against Garmin StreetCross libnaviliteprotocol.so.
 *
 * Wire: header(12) = "nAl@" | version | frameType | service | payloadSize u32 LE | payloadType,
 *       then CRC-32/MPEG-2 u32 LE over header+payload, then payload.
 */
object NaviLite {
    val MAGIC = byteArrayOf(0x6E, 0x41, 0x6C, 0x40) // "nAl@"
    const val VERSION = 1
    const val HEADER_SIZE = 12
    const val PACKET_OVERHEAD = HEADER_SIZE + 4

    /** Every TFT → phone message is tiny; a larger length means we locked onto garbage. */
    const val MAX_INCOMING_PAYLOAD = 64 * 1024

    const val MAX_STRING = 144
    const val MAX_ROAD_NAME = 64

    fun encode(frameType: Int, service: Int, payloadType: Int, payload: ByteArray): ByteArray {
        val out = ByteBuffer.allocate(PACKET_OVERHEAD + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put(MAGIC).put(VERSION.toByte()).put(frameType.toByte()).put(service.toByte())
            .putInt(payload.size).put(payloadType.toByte())
        val crc = crc32Mpeg2(payload, init = crc32Mpeg2(out.array(), 0, HEADER_SIZE))
        out.putInt(crc).put(payload)
        return out.array()
    }

    /** Diacritic-free printable ASCII, the only text the dash renders. Truncated to [maxBytes]. */
    fun dashAscii(text: String, maxBytes: Int = MAX_STRING): ByteArray {
        val folded = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('·', '-')
            .replace(Regex("[^\\x20-\\x7E]"), "")
            .replace(Regex(" {2,}"), " ")
            .trim()
        return folded.take(maxBytes).toByteArray(Charsets.US_ASCII)
    }
}

object FrameType {
    const val MOBILE_REQUEST = 1 // TFT → phone requests (48-56)
    const val MOBILE_UPDATE = 3 // TFT → phone updates (65, 66, 69, 70, 80, 82, 83)
    const val MCU_UPDATE = 6 // every phone → TFT message
}

object PayloadType {
    const val VALUE = 0 // exactly 2 bytes, int16 LE
    const val POINTER = 1 // structured payload
}

private val CRC_TABLE = IntArray(256) { i ->
    var c = i shl 24
    repeat(8) { c = if (c and Int.MIN_VALUE != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
    c
}

/** CRC-32/MPEG-2: poly 0x04C11DB7, init 0xFFFFFFFF, MSB-first, no final XOR. */
fun crc32Mpeg2(data: ByteArray, from: Int = 0, to: Int = data.size, init: Int = -1): Int {
    var crc = init
    for (i in from until to) {
        crc = CRC_TABLE[((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF] xor (crc shl 8)
    }
    return crc
}

class NaviLitePacket(
    val frameType: Int,
    val service: Int,
    val payloadType: Int,
    val payload: ByteArray,
)

/**
 * Turns an RFCOMM byte stream into packets. Handles split reads, several packets per read,
 * and garbage: on a bad length, version or CRC it skips one byte and re-syncs on the next magic.
 * Not thread-safe; feed it from the single reader coroutine.
 */
class NaviLiteDecoder {
    private var buf = ByteArray(4096)
    private var size = 0

    /** Packets dropped for a bad CRC/length/version since creation. */
    var rejected = 0
        private set

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<NaviLitePacket> {
        if (size + length > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + length))
        bytes.copyInto(buf, size, 0, length)
        size += length

        val packets = mutableListOf<NaviLitePacket>()
        var start = 0
        while (true) {
            val magicAt = indexOfMagic(start)
            if (magicAt < 0) {
                start = maxOf(start, size - (NaviLite.MAGIC.size - 1)) // keep a possible partial magic
                break
            }
            start = magicAt
            if (size - start < NaviLite.PACKET_OVERHEAD) break

            val header = ByteBuffer.wrap(buf, start, NaviLite.PACKET_OVERHEAD).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf[start + 4].toInt()
            val payloadSize = header.getInt(start + 7)
            if (version != NaviLite.VERSION || payloadSize !in 0..NaviLite.MAX_INCOMING_PAYLOAD) {
                rejected++; start++; continue
            }
            val end = start + NaviLite.PACKET_OVERHEAD + payloadSize
            if (end > size) break

            val payloadStart = start + NaviLite.PACKET_OVERHEAD
            val expected = header.getInt(start + NaviLite.HEADER_SIZE)
            val actual = crc32Mpeg2(buf, payloadStart, end, init = crc32Mpeg2(buf, start, start + NaviLite.HEADER_SIZE))
            if (expected != actual) {
                rejected++; start++; continue
            }
            packets += NaviLitePacket(
                frameType = buf[start + 5].toInt() and 0xFF,
                service = buf[start + 6].toInt() and 0xFF,
                payloadType = buf[start + 11].toInt() and 0xFF,
                payload = buf.copyOfRange(payloadStart, end),
            )
            start = end
        }
        buf.copyInto(buf, 0, start, size)
        size -= start
        return packets
    }

    private fun indexOfMagic(from: Int): Int {
        val m = NaviLite.MAGIC
        for (i in from..size - m.size) {
            if (buf[i] == m[0] && buf[i + 1] == m[1] && buf[i + 2] == m[2] && buf[i + 3] == m[3]) return i
        }
        return -1
    }
}
