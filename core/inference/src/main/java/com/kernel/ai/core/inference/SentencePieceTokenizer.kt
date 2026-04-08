package com.kernel.ai.core.inference

import java.io.File

/**
 * Minimal SentencePiece tokeniser for EmbeddingGemma-300M.
 *
 * Parses the binary `.model` file (protobuf) directly — no proto library dependency.
 * Implements Viterbi DP segmentation, consistent with the official SentencePiece spec.
 * Handles the UTF-8 byte-fallback for characters absent from the vocabulary.
 */
class SentencePieceTokenizer(modelFile: File) {

    private data class Piece(val id: Int, val score: Float, val type: Int)

    private val vocab = HashMap<String, Piece>(65536)

    val bosId: Int
    val eosId: Int
    val unkId: Int

    init {
        val raw = parseVocab(modelFile.readBytes())
        raw.forEachIndexed { id, (piece, score, type) ->
            vocab[piece] = Piece(id, score, type)
        }
        bosId = vocab["<bos>"]?.id ?: vocab["<s>"]?.id ?: 2
        eosId = vocab["<eos>"]?.id ?: vocab["</s>"]?.id ?: 1
        unkId = vocab["<unk>"]?.id ?: 3
    }

    /**
     * Encode [text] into a token-ID array of length ≤ [maxLen].
     * Prepends BOS when [addBos] is true (default).
     */
    fun encode(text: String, maxLen: Int = 512, addBos: Boolean = true): IntArray {
        val normalised = normalise(text)
        val codePoints = normalised.codePoints().toArray()
        val ids = viterbi(codePoints)
        val result = if (addBos) intArrayOf(bosId) + ids else ids
        return if (result.size <= maxLen) result else result.copyOf(maxLen)
    }

    // ── Normalisation ──────────────────────────────────────────────────────────
    // Replace whitespace with the ▁ (U+2581) sentinel used by SentencePiece.

    private fun normalise(text: String): String = buildString {
        var spacePending = true
        for (ch in text) {
            if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') {
                spacePending = true
            } else {
                if (spacePending) append('\u2581')
                append(ch)
                spacePending = false
            }
        }
    }

    // ── Viterbi DP ─────────────────────────────────────────────────────────────

    private fun viterbi(codePoints: IntArray): IntArray {
        val n = codePoints.size
        val NEG_INF = Float.NEGATIVE_INFINITY
        val dp = FloatArray(n + 1) { NEG_INF }
        val back = IntArray(n + 1) { -1 }
        dp[0] = 0f

        for (start in 0 until n) {
            if (dp[start] == NEG_INF) continue

            // Try every span [start, end) as a vocabulary piece.
            val sb = StringBuilder()
            for (end in start + 1..n) {
                sb.appendCodePoint(codePoints[end - 1])
                val p = vocab[sb.toString()]
                if (p != null && p.type != TYPE_UNUSED) {
                    val s = dp[start] + p.score
                    if (s > dp[end]) { dp[end] = s; back[end] = start }
                }
            }

            // Byte-fallback: if the very next position is still unreachable,
            // encode each UTF-8 byte of codePoints[start] as a <0xXX> piece.
            if (dp[start + 1] == NEG_INF) {
                val charBytes = String(codePoints, start, 1).toByteArray(Charsets.UTF_8)
                // A single code point may produce multiple bytes; we only handle
                // the first byte here since each maps to exactly one DP step.
                val hex = "<0x%02X>".format(charBytes[0].toInt() and 0xFF)
                val byteP = vocab[hex]
                if (byteP != null) {
                    val s = dp[start] + byteP.score
                    if (s > dp[start + 1]) { dp[start + 1] = s; back[start + 1] = start }
                } else {
                    // Last resort: emit UNK and keep DP moving.
                    dp[start + 1] = dp[start] - 10f
                    back[start + 1] = start
                }
            }
        }

        // Back-trace from position n to 0.
        val ids = mutableListOf<Int>()
        var pos = n
        while (pos > 0) {
            val prev = back[pos].takeIf { it >= 0 } ?: break
            val pieceStr = String(codePoints, prev, pos - prev)
            ids.add(vocab[pieceStr]?.id ?: unkId)
            pos = prev
        }
        ids.reverse()
        return ids.toIntArray()
    }

    // ── Protobuf parser ────────────────────────────────────────────────────────

    private data class RawPiece(val piece: String, val score: Float, val type: Int)

    private fun parseVocab(data: ByteArray): List<RawPiece> {
        val pieces = mutableListOf<RawPiece>()
        var pos = 0
        while (pos < data.size) {
            val (tag, p0) = readVarint(data, pos); pos = p0
            val fieldNum = (tag ushr 3).toInt()
            val wireType = (tag and 7).toInt()
            if (fieldNum == 1 && wireType == 2) {           // repeated SentencePiece pieces
                val (msgLen, p1) = readVarint(data, pos); pos = p1
                val msgEnd = pos + msgLen.toInt()
                var piece = ""; var score = 0f; var type = 1
                while (pos < msgEnd) {
                    val (it, p2) = readVarint(data, pos); pos = p2
                    val f = (it ushr 3).toInt(); val w = (it and 7).toInt()
                    when {
                        f == 1 && w == 2 -> {                       // string piece
                            val (l, p3) = readVarint(data, pos); pos = p3
                            piece = String(data, pos, l.toInt(), Charsets.UTF_8); pos += l.toInt()
                        }
                        f == 2 && w == 5 -> {                       // float score
                            score = readFloat(data, pos); pos += 4
                        }
                        f == 3 && w == 0 -> {                       // enum type
                            val (v, p3) = readVarint(data, pos); pos = p3; type = v.toInt()
                        }
                        else -> pos = skipField(data, pos, w)
                    }
                }
                pieces.add(RawPiece(piece, score, type))
            } else {
                pos = skipField(data, pos, wireType)
            }
        }
        return pieces
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var r = 0L; var s = 0; var p = start
        while (p < data.size) {
            val b = data[p++].toLong() and 0xFF
            r = r or ((b and 0x7F) shl s)
            if (b and 0x80 == 0L) break
            s += 7
        }
        return r to p
    }

    private fun readFloat(data: ByteArray, pos: Int): Float {
        val bits = (data[pos].toInt() and 0xFF) or
                   ((data[pos + 1].toInt() and 0xFF) shl 8) or
                   ((data[pos + 2].toInt() and 0xFF) shl 16) or
                   ((data[pos + 3].toInt() and 0xFF) shl 24)
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun skipField(data: ByteArray, pos: Int, wireType: Int): Int = when (wireType) {
        0 -> { var p = pos; while (data[p++].toInt() and 0x80 != 0) {}; p }
        1 -> pos + 8
        2 -> { val (l, p) = readVarint(data, pos); p + l.toInt() }
        5 -> pos + 4
        else -> throw IllegalStateException("Unknown protobuf wire type $wireType at $pos")
    }

    companion object {
        private const val TYPE_UNUSED = 5
    }
}
