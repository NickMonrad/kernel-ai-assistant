package com.kernel.ai.core.voice.asr4all

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Loads the unmodified FUTO config files and recorded model outputs under `src/test/resources/asr4all`. */
object Asr4AllTestData {
    private fun resource(path: String): File {
        val url = checkNotNull(javaClass.classLoader.getResource("asr4all/$path")) { "missing test resource asr4all/$path" }
        return File(url.toURI())
    }

    /** Directory laid out like a downloaded model (without the `.pte`). */
    fun modelDir(size: String): File = resource(size)

    fun text(size: String, file: String): String = File(modelDir(size), file).readText()

    val metadataPath = "${Asr4AllModelConfig.EXPORT_DIR}/metadata.json"

    fun configM(): Asr4AllModelConfig = Asr4AllModelConfig.load(modelDir("m"), Asr4AllSize.M)

    /** Recorded run of `1272-128104-0000` through the M export at `stream_c16r4`. */
    object Clip0000 {
        private val json: JsonObject by lazy {
            JsonParser.parseString(resource("fixtures/1272-128104-0000_m_stream_c16r4.json").readText()).asJsonObject
        }

        val frames: Int get() = json["logits_shape"].asJsonArray[0].asInt
        val vocabSize: Int get() = json["logits_shape"].asJsonArray[1].asInt

        val logits: FloatArray by lazy {
            val bytes = resource("fixtures/" + json["logits_file"].asString).readBytes()
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            FloatArray(buffer.remaining()).also { buffer.get(it) }
        }

        val ctcText: String get() = json["ctc_text"].asString

        val ctcTokens: List<Triple<Int, Int, Int>>
            get() = json["ctc_tokens"].asJsonArray.map {
                val o = it.asJsonObject
                Triple(o["id"].asInt, o["start"].asInt, o["end"].asInt)
            }

        val streamSlots: List<IntArray>
            get() = json["pcec_stream_slots"].asJsonArray.map { call -> call.asJsonArray.map { it.asInt }.toIntArray() }

        val flushSlots: IntArray get() = json["pcec_flush_slots"].asJsonArray.map { it.asInt }.toIntArray()
        val flushN: Int get() = json["pcec_flush_n"].asInt
    }
}
