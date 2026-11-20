package com.kernel.ai.core.voice.asr4all

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/** The three published asr4all model sizes. [codenameWord] appears in `metadata.json`'s codename. */
enum class Asr4AllSize(val repository: String, internal val codenameWord: String) {
    S("futo-org/asr4all-s", "small"),
    M("futo-org/asr4all-m", "medium"),
    L("futo-org/asr4all-l", "large"),
}

/** One streaming operating point ("tier") of the export. All counts are 25 Hz encoder frames. */
data class Asr4AllTier(
    val name: String,
    val commitFrames: Int,
    val lookaheadFrames: Int,
    val windowFrames: Int,
    /** Samples per encoder call: the method's fixed audio input length. */
    val windowSamples: Int,
    /** Samples the window advances by per call (`commitFrames * samplesPerFrame`). */
    val hopSamples: Int,
    /** Tokens per PCEC streaming call. */
    val pcecRung: Int,
    val pcecMethod: String,
    val pcecFlushMethod: String,
)

/** Punctuation, capitalisation and error-correction (PCEC) head configuration. */
data class Asr4AllPcecConfig(
    val vocab: List<String>,
    val blankId: Int,
    val capId: Int,
    val allCapsId: Int,
    val periodId: Int,
    val commaId: Int,
    val questionId: Int,
    /** Input id for unused token positions (the acoustic blank id). */
    val padInputId: Int,
    /** Number of input ids the head accepts (acoustic vocabulary plus pad and gap). */
    val inputVocabSize: Int,
    /** Number of pause buckets; index [pauseBuckets] is the "blackout" (unknown pause) bucket. */
    val pauseBuckets: Int,
    val pauseBlackout: Int,
    /** Tokens the head holds back before it emits their slots (attention right context × superblocks). */
    val lagTokens: Int,
    /** Output slots per input token: one optional insertion slot plus the kept input token. */
    val slotsPerToken: Int,
)

/**
 * Parsed and cross-checked asr4all export configuration. Every tensor layout the engine
 * relies on is derived from these files; [Asr4AllModelConfig.load] rejects inconsistent or
 * mismatched files with [Asr4AllConfigException].
 *
 * Expected directory layout (the unmodified files from the FUTO repository):
 * `config.json`, `release_geom.json`, `preprocessor_config.json` and
 * `executorch/xnnpack_int8/{metadata.json, asr_encoder.pte}`.
 */
class Asr4AllModelConfig private constructor(
    val size: Asr4AllSize,
    val codename: String,
    val sampleRate: Int,
    val samplesPerFrame: Int,
    val vocab: List<String>,
    val blankId: Int,
    /** Width of the encoder's per-frame hidden output (`config.json` `d_model`). */
    val hiddenDim: Int,
    val filterCacheNumel: Int,
    val attnCacheNumel: Int,
    val tiers: Map<String, Asr4AllTier>,
    val defaultTier: String,
    val pcec: Asr4AllPcecConfig,
    val encoderFile: File,
) {
    val vocabSize: Int get() = vocab.size + 1

    fun tier(name: String?): Asr4AllTier {
        val key = name ?: defaultTier
        return tiers[key] ?: throw Asr4AllConfigException("tier '$key' is not in this export (has ${tiers.keys})")
    }

    companion object {
        const val EXPORT_DIR = "executorch/xnnpack_int8"

        /** Loads and validates the files in [modelDir] against [size]. */
        fun load(modelDir: File, size: Asr4AllSize): Asr4AllModelConfig {
            val exportDir = File(modelDir, EXPORT_DIR)
            return parse(
                size = size,
                metadataJson = readFile(File(exportDir, "metadata.json")),
                releaseGeomJson = readFile(File(modelDir, "release_geom.json")),
                configJson = readFile(File(modelDir, "config.json")),
                preprocessorJson = readFile(File(modelDir, "preprocessor_config.json")),
                exportDir = exportDir,
            )
        }

        fun parse(
            size: Asr4AllSize,
            metadataJson: String,
            releaseGeomJson: String,
            configJson: String,
            preprocessorJson: String,
            exportDir: File,
        ): Asr4AllModelConfig = try {
            build(
                size,
                parseObject(metadataJson, "metadata.json"),
                parseObject(releaseGeomJson, "release_geom.json"),
                parseObject(configJson, "config.json"),
                parseObject(preprocessorJson, "preprocessor_config.json"),
                exportDir,
            )
        } catch (e: Asr4AllConfigException) {
            throw e
        } catch (e: RuntimeException) {
            // Missing keys or wrong JSON types (ClassCastException, IllegalStateException, ...).
            throw Asr4AllConfigException("malformed asr4all config: ${e.message}", e)
        }

        private fun build(
            size: Asr4AllSize,
            meta: JsonObject,
            geom: JsonObject,
            config: JsonObject,
            pre: JsonObject,
            exportDir: File,
        ): Asr4AllModelConfig {
            check(meta.int("schema_version") == 1, "metadata.json schema_version ${meta.int("schema_version")} is not 1")
            check(meta.str("kind") == "asr_ctc", "metadata.json kind '${meta.str("kind")}' is not asr_ctc")
            val codename = meta.str("codename")
            check(
                codename.split('_').any { it.startsWith(size.codenameWord) },
                "metadata.json codename '$codename' is not a ${size.name} (${size.codenameWord}) export",
            )

            // Audio input format: preprocessor_config.json defines raw mono PCM at sampling_rate.
            val sampleRate = meta.int("sample_rate")
            check(pre.int("sampling_rate") == sampleRate, "preprocessor sampling_rate ${pre.int("sampling_rate")} != metadata sample_rate $sampleRate")
            check(pre.int("feature_size") == 1, "preprocessor feature_size ${pre.int("feature_size")} is not mono")
            check(!pre.bool("do_normalize"), "preprocessor do_normalize=true is not supported (engine feeds raw PCM)")

            val streaming = geom.obj("streaming")
            val samplesPerFrame = meta.int("samples_per_frame")
            check(streaming.int("samples_per_frame") == samplesPerFrame, "release_geom samples_per_frame != metadata samples_per_frame")
            val samplesMultiple = streaming.int("samples_multiple_of")

            val vocab = meta.arr("vocab").map { it.asString }
            val vocabSize = meta.int("vocab_size")
            val blankId = meta.int("blank_id")
            check(vocab.size + 1 == vocabSize, "metadata vocab has ${vocab.size} entries, vocab_size $vocabSize")
            check(blankId == vocabSize - 1, "metadata blank_id $blankId is not the last id")
            check(config.int("vocab_size") == vocabSize, "config.json vocab_size ${config.int("vocab_size")} != metadata $vocabSize")
            check(config.int("blank_id") == blankId && geom.int("blank_id") == blankId, "blank_id differs between files")
            check(config.arr("vocab").map { it.asString } == vocab, "config.json vocab differs from metadata vocab")

            val depth = meta.obj("provenance").int("depth")
            check(config.int("n_superblocks") == depth, "config.json n_superblocks ${config.int("n_superblocks")} != metadata depth $depth (files from different sizes?)")
            val hiddenDim = config.int("d_model")

            val pcec = parsePcec(meta.obj("pcec"), geom.obj("pcec"), config.obj("pcec"), vocabSize)
            val tiers = parseTiers(meta, geom, config, samplesPerFrame, samplesMultiple)
            val defaultTier = meta.str("default_tier")
            check(geom.str("default_tier") == defaultTier && defaultTier in tiers, "default tier '$defaultTier' is inconsistent")

            val encoderFile = File(exportDir, meta.obj("files").str("encoder"))
            return Asr4AllModelConfig(
                size = size,
                codename = codename,
                sampleRate = sampleRate,
                samplesPerFrame = samplesPerFrame,
                vocab = vocab,
                blankId = blankId,
                hiddenDim = hiddenDim,
                filterCacheNumel = streaming.int("filter_cache_numel"),
                attnCacheNumel = streaming.int("attn_cache_numel"),
                tiers = tiers,
                defaultTier = defaultTier,
                pcec = pcec,
                encoderFile = encoderFile,
            )
        }

        private fun parseTiers(
            meta: JsonObject,
            geom: JsonObject,
            config: JsonObject,
            samplesPerFrame: Int,
            samplesMultiple: Int,
        ): Map<String, Asr4AllTier> {
            val geomTiers = geom.obj("tiers")
            val provenance = meta.obj("provenance").obj("tier_geometry")
            val tierRung = geom.obj("pcec").obj("tier_rung")
            val rungs = geom.obj("pcec").obj("rungs")
            val configOps = config.arr("tiers").map { op -> op.asJsonArray.let { it[0].asInt to it[1].asInt } }.toSet()
            val result = LinkedHashMap<String, Asr4AllTier>()
            for (entry in meta.arr("tiers")) {
                val t = entry.asJsonObject
                val name = t.str("name")
                val g = geomTiers.objOrNull(name) ?: fail("tier $name missing from release_geom.json")
                val p = provenance.objOrNull(name) ?: fail("tier $name missing from metadata tier_geometry")
                val commit = g.int("commit_frames")
                val lookahead = g.int("lookahead_frames")
                val window = g.int("window_frames")
                check(
                    p.int("commit_frames") == commit && p.int("receptive_lookahead_frames") == lookahead && p.int("window_frames") == window,
                    "tier $name geometry differs between release_geom.json and metadata.json (files from different sizes?)",
                )
                check(window == commit + lookahead, "tier $name window $window != commit $commit + lookahead $lookahead")
                check((commit to g.int("right_ctx")) in configOps, "tier $name (c$commit r${g.int("right_ctx")}) not in config.json tiers")
                val windowSamples = g.int("window_samples")
                check(
                    windowSamples % samplesMultiple == 0 && windowSamples >= window * samplesPerFrame,
                    "tier $name window_samples $windowSamples is not a whole number of frames covering the window",
                )
                val rung = tierRung.int(name)
                val rungInfo = rungs.objOrNull(rung.toString()) ?: fail("PCEC rung $rung for tier $name missing")
                val pcecRef = t.obj("pcec")
                check(
                    pcecRef.str("name") == rungInfo.str("name") && pcecRef.str("flush") == rungInfo.str("flush"),
                    "tier $name PCEC methods differ between metadata.json and release_geom.json",
                )
                result[name] = Asr4AllTier(
                    name = name,
                    commitFrames = commit,
                    lookaheadFrames = lookahead,
                    windowFrames = window,
                    windowSamples = windowSamples,
                    hopSamples = commit * samplesPerFrame,
                    pcecRung = rung,
                    pcecMethod = rungInfo.str("name"),
                    pcecFlushMethod = rungInfo.str("flush"),
                )
            }
            check(result.isNotEmpty(), "metadata.json lists no tiers")
            return result
        }

        private fun parsePcec(meta: JsonObject, geom: JsonObject, config: JsonObject, acousticVocabSize: Int): Asr4AllPcecConfig {
            val vocab = meta.arr("vocab").map { it.asString }
            val ctrl = meta.obj("ctrl")
            val vocabSize = meta.int("vocab_size")
            val blankId = meta.int("blank_id")
            check(blankId == vocabSize - 1, "PCEC blank_id $blankId is not the last id")
            check(geom.int("blank_id") == blankId && geom.int("vocab_size") == vocabSize, "PCEC vocab differs between files")
            val ctrlIds = listOf("cap", "allcaps", "period", "comma", "question").map { ctrl.int(it) }
            check(
                ctrlIds == (vocab.size until vocab.size + ctrlIds.size).toList() && vocab.size + ctrlIds.size == blankId,
                "PCEC control ids $ctrlIds do not follow the ${vocab.size}-entry vocabulary",
            )
            val arch = config.obj("arch")
            val padIn = meta.int("pad_in")
            check(padIn == config.int("pad_in") && padIn == geom.int("pad_in"), "PCEC pad_in differs between files")
            val inputVocab = arch.int("in_vocab")
            check(padIn < inputVocab && acousticVocabSize <= inputVocab, "PCEC in_vocab $inputVocab does not cover the acoustic vocabulary")
            val blackout = meta.int("pause_blackout")
            check(blackout == config.int("pause_blackout"), "PCEC pause_blackout differs between files")
            check(arch.bool("keep_input") == meta.bool("keep_input"), "PCEC keep_input differs between files")
            return Asr4AllPcecConfig(
                vocab = vocab,
                blankId = blankId,
                capId = ctrlIds[0],
                allCapsId = ctrlIds[1],
                periodId = ctrlIds[2],
                commaId = ctrlIds[3],
                questionId = ctrlIds[4],
                padInputId = padIn,
                inputVocabSize = inputVocab,
                pauseBuckets = arch.int("pause_buckets"),
                pauseBlackout = blackout,
                lagTokens = arch.int("attn_right") * arch.int("n_superblocks"),
                slotsPerToken = if (arch.bool("keep_input")) 2 else 1,
            )
        }

        private fun readFile(file: File): String {
            if (!file.isFile) throw Asr4AllConfigException("missing model file ${file.path}")
            return file.readText()
        }

        private fun parseObject(json: String, name: String): JsonObject = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: RuntimeException) {
            throw Asr4AllConfigException("$name is not a JSON object", e)
        }

        private fun check(condition: Boolean, message: String) {
            if (!condition) throw Asr4AllConfigException(message)
        }

        private fun fail(message: String): Nothing = throw Asr4AllConfigException(message)

        private fun JsonObject.req(key: String): JsonElement = get(key)?.takeUnless { it.isJsonNull } ?: fail("missing key '$key'")
        private fun JsonObject.int(key: String): Int = req(key).asInt
        private fun JsonObject.str(key: String): String = req(key).asString
        private fun JsonObject.bool(key: String): Boolean = req(key).asBoolean
        private fun JsonObject.obj(key: String): JsonObject = req(key).asJsonObject
        private fun JsonObject.arr(key: String): JsonArray = req(key).asJsonArray
        private fun JsonObject.objOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    }
}
