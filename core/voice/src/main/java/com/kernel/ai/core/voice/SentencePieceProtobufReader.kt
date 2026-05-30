package com.kernel.ai.core.voice

/**
 * Shared protobuf field reader for SentencePiece `.model` binary files.
 *
 * SentencePiece stores vocabulary as a repeated `SentencePiece` message inside a
 * `TrainTextProto` message. Each piece has:
 * - field 1 (length-delimited): `piece` (String)
 * - field 2 (32-bit float): `score` (Float)
 * - field 3 (varint): `type` (enum: NORMAL=0, UNKNOWN=1, USER=2, UNUSED=5)
 *
 * This parser reads fields by their protobuf field number, not by position,
 * making it robust against reordering or new fields added by SentencePiece.
 */
object SentencePieceProtobufReader {
    /** A single vocabulary entry from a SentencePiece model file. */
    data class RawPiece(val piece: String, val score: Float, val type: Int)


    /**
     * Parse all SentencePiece entries from a binary `.model` file.
     *
     * Format: top-level repeated `SentencePiece` messages (field 2, wire type 2).
     * Each message contains nested fields 1 (string), 2 (float), 3 (varint).
     */
    fun parseVocab(data: ByteArray): List<RawPiece> {
        val pieces = mutableListOf<RawPiece>()
        var pos = 0

        while (pos < data.size) {
            val (tag, next) = readTag(data, pos)
            pos = next

            val fieldNum = tag ushr 3
            val wireType = (tag and 7).toInt()

            // Top-level: field 2 = repeated SentencePiece message (length-delimited)
            if (fieldNum == 2L && wireType == 2) {
                val (msgLen, msgStart) = readVarint(data, pos)
                pos = msgStart
                val msgEnd = pos + msgLen.toInt()

                var piece = ""; var score = 0f; var type = 1 // default = UNKNOWN
                while (pos < msgEnd) {
                    val (innerTag, innerNext) = readTag(data, pos)
                    pos = innerNext

                    val innerField = innerTag ushr 3
                    val innerWire = (innerTag and 7).toInt()

                    when {
                        innerField == 1L && innerWire == 2 -> {
                            // String piece
                            val (strLen, strStart) = readVarint(data, pos)
                            pos = strStart
                            piece = String(data.copyOfRange(strStart, strStart + strLen.toInt()), Charsets.UTF_8)
                            pos += strLen.toInt()
                        }
                        innerField == 2L && innerWire == 5 -> {
                            // Float score
                            score = readFloat(data, pos)
                            pos += 4
                        }
                        innerField == 3L && innerWire == 0 -> {
                            // Enum type
                            val (typeVal, typeNext) = readVarint(data, pos)
                            pos = typeNext
                            type = typeVal.toInt()
                        }
                        else -> {
                            pos = skipField(data, pos, innerWire)
                        }
                    }
                }
                pieces.add(RawPiece(piece, score, type))
            } else {
                pos = skipField(data, pos, wireType)
            }
        }

        return pieces
    }

    // ── Low-level protobuf primitives ────────────────────────────────────────

    /** Read a protobuf tag: (fieldNumber << 3) | wireType. */
    private fun readTag(data: ByteArray, pos: Int): Pair<Long, Int> {
        val byte = data[pos].toInt() and 0xFF
        return Pair(byte.toLong() ushr 3, byte and 7)
    }

    /** Read a protobuf varint (wire type 0). */
    fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (pos < data.size) {
            val byte = data[pos].toInt() and 0xFF
            result = result or ((byte and 0x7F).toLong() shl shift)
            shift += 7
            pos++
            if ((byte and 0x80) == 0) break
        }
        return Pair(result, pos)
    }

    /** Read a 32-bit float (wire type 5, little-endian). */
    private fun readFloat(data: ByteArray, pos: Int): Float {
        return java.nio.ByteBuffer.wrap(data, pos, 4).float
    }

    /** Skip a field based on wire type. Returns new position. */
    fun skipField(data: ByteArray, pos: Int, wireType: Int): Int {
        return when (wireType) {
            0 -> { // Varint
                var p = pos
                while (p < data.size && (data[p].toInt() and 0x80) != 0) p++
                p + 1
            }
            1 -> { // 64-bit
                pos + 8
            }
            2 -> { // Length-delimited
                val (len, next) = readVarint(data, pos)
                next + len.toInt()
            }
            5 -> { // 32-bit
                pos + 4
            }
            else -> pos + 1 // Unknown wire type — skip one byte
        }
    }
    const val TYPE_UNUSED = 5
}
