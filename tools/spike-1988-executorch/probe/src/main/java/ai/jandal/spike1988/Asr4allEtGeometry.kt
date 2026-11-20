package ai.jandal.spike1988

/**
 * Method geometry of the pinned FUTO `executorch/xnnpack_int8/asr_encoder.pte` bundles, read on host
 * with the ExecuTorch 1.5.1 Python runtime (`MethodMeta`). The Java API exposes no tensor metadata.
 *
 * Pins (Hugging Face revision, PTE SHA-256):
 *  - futo-org/asr4all-s@87a6b5d093723fe37e9f69ad26307a3b95a31af0  7d5679688ca57a7e42d4b42e093b7a29b85d595f454d41cf0aa5f95e7c8c27ec
 *  - futo-org/asr4all-m@7d6e9145109a5bece4f05831d046e93d27e38a99  d08648b914a90669986f943011099fb518783ca0bebb3fd9a4ded53ad5067763
 *  - futo-org/asr4all-l@79d1c23bc8f1174cee576b40bbaa0118bd95abed  2670068ab08a2a40d76e6cb79d492ce6879d7165c5f6a8defbf2f4994a49f760
 */
data class Asr4allEtGeometry(
    val window: Int,
    val commit: Int,
    val filter: Int,
    val attn: Int,
    val hidden: Int,
    val vocab: Int = 257,
) {
    companion object {
        private data class Size(val c16: Int, val c32: Int, val filter: Int, val attn: Int, val hidden: Int)

        private val sizes = mapOf(
            "s" to Size(22_400, 42_880, 1_006_080, 329_090, 384),
            "m" to Size(24_960, 48_000, 1_728_000, 548_354, 512),
            "l" to Size(27_520, 53_120, 2_545_920, 822_402, 640),
        )

        val PTE_SHA256 = mapOf(
            "s" to "7d5679688ca57a7e42d4b42e093b7a29b85d595f454d41cf0aa5f95e7c8c27ec",
            "m" to "d08648b914a90669986f943011099fb518783ca0bebb3fd9a4ded53ad5067763",
            "l" to "2670068ab08a2a40d76e6cb79d492ce6879d7165c5f6a8defbf2f4994a49f760",
        )

        fun of(size: String, tier: String): Asr4allEtGeometry {
            val s = sizes[size] ?: error("unknown size $size")
            return when (tier) {
                "stream_c16r4" -> Asr4allEtGeometry(s.c16, 16, s.filter, s.attn, s.hidden)
                "stream_c32r8" -> Asr4allEtGeometry(s.c32, 32, s.filter, s.attn, s.hidden)
                else -> error("unsupported tier $tier")
            }
        }
    }
}
