package com.lampp.nmaxconnect.core.protocol.ccu

import java.io.ByteArrayOutputStream

/**
 * Just enough proto3 for the CCU's ~6 vehicle-data messages: varint and length-delimited fields,
 * packed repeated ints. Field numbers come from Y-Connect's generated classes (docs/bike-protocol.md §6.5).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    /** Proto3 omits default values; so do we. Negative ints use 10-byte varints, as int32 does. */
    fun int(field: Int, value: Int) = apply { if (value != 0) { tag(field, VARINT); varint(value.toLong()) } }

    fun bool(field: Int, value: Boolean) = apply { if (value) { tag(field, VARINT); varint(1) } }

    /** uint32: a negative Int is its 32-bit two's complement, not a 10-byte int32 varint. */
    fun uint32(field: Int, value: Int) = apply { if (value != 0) { tag(field, VARINT); varint(value.toLong() and 0xFFFFFFFFL) } }

    fun string(field: Int, value: String) = bytes(field, value.toByteArray(Charsets.UTF_8))

    fun bytes(field: Int, value: ByteArray) = apply {
        if (value.isNotEmpty()) { tag(field, LEN); varint(value.size.toLong()); out.write(value) }
    }

    fun message(field: Int, value: ProtoWriter) = bytes(field, value.toByteArray())

    fun packedInts(field: Int, values: List<Int>) = apply {
        if (values.isNotEmpty()) bytes(field, ProtoWriter().apply { values.forEach { varint(it.toLong()) } }.toByteArray())
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = varint(((field shl 3) or wireType).toLong())

    private fun varint(value: Long) {
        var v = value
        while (v and 0x7FL.inv() != 0L) { out.write(((v and 0x7F) or 0x80).toInt()); v = v ushr 7 }
        out.write(v.toInt())
    }

    companion object {
        const val VARINT = 0
        const val LEN = 2
    }
}

/** One decoded field. [varint] is set for wire type 0, [bytes] for wire type 2. */
class ProtoField(val number: Int, val varint: Long = 0, val bytes: ByteArray? = null)

/** Reads every field of one message; throws [IllegalArgumentException] on truncated or unsupported input. */
class ProtoMessage(data: ByteArray) {
    val fields: List<ProtoField> = parse(data)

    fun int(field: Int): Int = fields.lastOrNull { it.number == field && it.bytes == null }?.varint?.toInt() ?: 0

    fun messages(field: Int): List<ProtoMessage> = fields.filter { it.number == field && it.bytes != null }.map { ProtoMessage(it.bytes!!) }

    fun bytes(field: Int): ByteArray = fields.lastOrNull { it.number == field && it.bytes != null }?.bytes ?: ByteArray(0)

    /** Repeated int32, accepted packed or unpacked as proto3 parsers must. */
    fun ints(field: Int): List<Int> = fields.filter { it.number == field }.flatMap { f ->
        f.bytes?.let { packed -> Reader(packed).run { buildList { while (hasMore) add(varint().toInt()) } } } ?: listOf(f.varint.toInt())
    }

    private fun parse(data: ByteArray): List<ProtoField> = Reader(data).run {
        buildList {
            while (hasMore) {
                val key = varint()
                val number = (key ushr 3).toInt()
                require(number > 0) { "field number 0" }
                when (val type = (key and 7).toInt()) {
                    ProtoWriter.VARINT -> add(ProtoField(number, varint = varint()))
                    ProtoWriter.LEN -> add(ProtoField(number, bytes = readBytes(varint().toInt())))
                    I64 -> skip(8)
                    I32 -> skip(4)
                    else -> throw IllegalArgumentException("wire type $type")
                }
            }
        }
    }

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        val hasMore get() = pos < data.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < data.size && shift < 64) { "truncated varint" }
                val b = data[pos++].toInt()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun readBytes(n: Int): ByteArray {
            require(n >= 0 && pos + n <= data.size) { "truncated field" }
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }

        fun skip(n: Int) { readBytes(n) }
    }

    private companion object {
        const val I64 = 1
        const val I32 = 5
    }
}
