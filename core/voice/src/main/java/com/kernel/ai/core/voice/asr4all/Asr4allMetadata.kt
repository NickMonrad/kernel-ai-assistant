package com.kernel.ai.core.voice.asr4all

import com.google.gson.JsonObject

/**
 * The parts of an asr4all release `metadata.json` the streaming port reads. The reference driver
 * keeps two vocabularies: the top-level one (CTC text of the acoustic pass) and the `pcec` section's
 * own one (rendering, and the PCEC feed). Both are kept here, as the reference uses them.
 */
internal class Asr4allMetadata(json: JsonObject) {
    /** Acoustic CTC vocabulary and blank id (top level). */
    val vocab: List<String> = json.getAsJsonArray("vocab").map { it.asString }
    val blankId: Int = json.int("blank_id")
    val samplesPerFrame: Int = json.getAsJsonObject("streaming").int("samples_per_frame")
    val graphs: Map<String, String> =
        json.getAsJsonObject("graphs").entrySet().associate { it.key to it.value.asString }
    val weightsFile: String? = json.get("weights")?.takeUnless { it.isJsonNull }?.asString
    val tierNames: List<String> = json.getAsJsonObject("tiers").keySet().toList()
    val defaultTier: String = json.get("default_tier").asString

    /** PCEC section: its own vocabulary, blank, control ids, and feed padding (the reference's `meta["pcec"]`). */
    private val pcec: JsonObject = json.getAsJsonObject("pcec")
    val pcecVocab: List<String> = pcec.getAsJsonArray("vocab").map { it.asString }
    val pcecBlankId: Int = pcec.int("blank_id")
    val padIn: Long = pcec.long("pad_in")
    val pauseBlackout: Long = pcec.long("pause_blackout")
    val realPause: Boolean = pcec.get("real_pause")?.takeUnless { it.isJsonNull }?.asBoolean ?: false
    val ctrl: Ctrl = pcec.getAsJsonObject("ctrl").let {
        Ctrl(
            cap = it.int("cap"),
            allcaps = it.int("allcaps"),
            period = it.int("period"),
            comma = it.int("comma"),
            question = it.int("question"),
        )
    }

    /** PCEC rung by key ("16", "64"). */
    val rungs: Map<String, Rung> = pcec.getAsJsonObject("rungs").entrySet().associate { (key, value) ->
        val r = value.asJsonObject
        key to Rung(
            name = r.get("name").asString,
            flush = r.get("flush").asString,
            cacheTensors = r.get("cache_tensors")?.asInt ?: 2,
        )
    }

    /** Tier name -> PCEC rung key. */
    val tierRung: Map<String, String> = pcec.getAsJsonObject("tier_rung").entrySet().associate { (key, value) ->
        key to value.asInt.toString()
    }

    internal data class Ctrl(val cap: Int, val allcaps: Int, val period: Int, val comma: Int, val question: Int)

    internal data class Rung(val name: String, val flush: String, val cacheTensors: Int)

    private fun JsonObject.int(key: String): Int = get(key).asInt
    private fun JsonObject.long(key: String): Long = get(key).asLong
}
