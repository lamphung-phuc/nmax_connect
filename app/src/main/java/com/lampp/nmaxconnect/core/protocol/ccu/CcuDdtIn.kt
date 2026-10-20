package com.lampp.nmaxconnect.core.protocol.ccu

import java.util.UUID

/**
 * DDT-in (CCU → phone bulk) receiver, as motoconsdk's DdtToPhoneSession:
 * header notify `[id][len u24]` on DDT_IN_HEADER → ack `[01 id]` on DDT_IN_ACK;
 * each data notify `[id][off u24][bytes]` on DDT_IN_DATA → ack `[id]` on DDT_IN_DATA, or `[00 00]` on
 * DDT_IN_ACK once complete. A new header replaces any unfinished transfer.
 */
class CcuDdtIn {
    /** A GATT write the receiver owes the CCU, plus the completed message (`[cmd u16][body]`) if any. */
    class Step(val writeTo: UUID, val value: ByteArray, val message: ByteArray? = null)

    private var id: Byte? = null
    private var buf = ByteArray(0)
    private var received = 0

    fun header(v: ByteArray): Step? {
        if (v.size < 4) return null
        val len = readUnsigned(v, 1, 3).toInt()
        if (len == 0 || len > MAX_MESSAGE) return null
        id = v[0]
        buf = ByteArray(len)
        received = 0
        return Step(CcuUuids.DDT_IN_ACK, byteArrayOf(1, v[0]))
    }

    fun data(v: ByteArray): Step? {
        val current = id ?: return null
        if (v.size < 4 || v[0] != current) return null
        val offset = readUnsigned(v, 1, 3).toInt()
        val n = v.size - 4
        if (offset + n > buf.size) return null
        v.copyInto(buf, offset, 4, v.size)
        received += n
        if (received < buf.size) return Step(CcuUuids.DDT_IN_DATA, byteArrayOf(current))
        id = null
        return Step(CcuUuids.DDT_IN_ACK, byteArrayOf(0, 0), message = buf)
    }

    private companion object {
        const val MAX_MESSAGE = 64 * 1024
    }
}

/**
 * DDT v2 (E974 notify) and v3-in (56DC indicate) receiver: `[seq u16][part 1..n][total][bytes]`,
 * parts of one seq concatenated in order. Unfinished seqs expire after [timeoutMs] (Y-Connect: 3 s).
 */
class CcuPartAssembler(private val timeoutMs: Long = 3_000, private val now: () -> Long = System::currentTimeMillis) {
    private class Pending(total: Int) {
        val parts = arrayOfNulls<ByteArray>(total)
        var touched = 0L
    }

    private val pending = HashMap<Int, Pending>()

    /** Returns the completed message, or null while parts are missing or the frame is malformed. */
    fun feed(v: ByteArray): ByteArray? {
        val t = now()
        pending.values.removeAll { t - it.touched > timeoutMs }
        if (v.size < 4) return null
        val seq = readU16(v, 0)
        val part = v[2].toInt() and 0xFF
        val total = v[3].toInt() and 0xFF
        if (part !in 1..total) return null

        val p = pending[seq]?.takeIf { it.parts.size == total } ?: Pending(total)
        p.parts[part - 1] = v.copyOfRange(4, v.size)
        p.touched = t
        if (p.parts.any { it == null }) {
            pending[seq] = p
            return null
        }
        pending.remove(seq)
        return p.parts.fold(ByteArray(0)) { acc, b -> acc + b!! }
    }
}
