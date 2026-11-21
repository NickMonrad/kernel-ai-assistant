package ai.jandal.asr4alltier

import java.io.File
import java.util.Locale

/**
 * Jandal's shipping offline default: sherpa-onnx streaming Zipformer
 * (csukuangfj/sherpa-onnx-streaming-zipformer-en-2023-02-21 int8), through the app's own Sherpa AAR, with the
 * shipping `SherpaOnnxVoiceInputController.initOnlineRecognizer` configuration (numThreads 2, cpu, greedy,
 * maxActivePaths 4, default endpoint rules). For paired WER the whole clip is decoded and finalised at EOF,
 * like the asr4all lanes; [endpointsBeforeEof] records whether the shipping loop would have stopped earlier.
 */
class ZipformerLane(dir: File) : StreamingLane {
    private val prefix = "com.k2fsa.sherpa.onnx."
    private fun cls(name: String) = Class.forName(prefix + name)
    private val recognizerClass = cls("OnlineRecognizer")
    private val recognizer: Any
    private val createStream = recognizerClass.getDeclaredMethod("createStream", String::class.java)
    private val streamClass: Class<*>
    private val accept: java.lang.reflect.Method
    private val inputFinished: java.lang.reflect.Method
    private val releaseStream: java.lang.reflect.Method
    private val isReady: java.lang.reflect.Method
    private val decode: java.lang.reflect.Method
    private val isEndpoint: java.lang.reflect.Method
    private val getResult: java.lang.reflect.Method
    private val getText: java.lang.reflect.Method
    private var stream: Any
    private var lastText = ""
    var endpointsBeforeEof = 0; private set
    override val description = "sherpa-onnx-1.13.0 zipformer-en-2023-02-21 int8 (shipping config)"

    init {
        fun set(target: Any, name: String, value: Any) {
            val type = when (value) {
                is Int -> Int::class.javaPrimitiveType
                is Boolean -> Boolean::class.javaPrimitiveType
                else -> value.javaClass
            }
            val setter = "set" + name.replaceFirstChar { it.uppercase() }
            runCatching { target.javaClass.getDeclaredMethod(setter, type).also { it.isAccessible = true }.invoke(target, value) }
                .recoverCatching { target.javaClass.getDeclaredField(name).also { it.isAccessible = true }.set(target, value) }
                .getOrThrow()
        }
        fun file(name: String) = File(dir, name).also { require(it.isFile) { "missing $it" } }.absolutePath
        val feat = cls("FeatureConfig").getDeclaredConstructor().newInstance().also {
            set(it, "sampleRate", 16_000)
            set(it, "featureDim", 80)
        }
        val transducer = cls("OnlineTransducerModelConfig").getDeclaredConstructor().newInstance().also {
            set(it, "encoder", file("sherpa-stt-encoder.int8.onnx"))
            set(it, "decoder", file("sherpa-stt-decoder.int8.onnx"))
            set(it, "joiner", file("sherpa-stt-joiner.int8.onnx"))
        }
        val model = cls("OnlineModelConfig").getDeclaredConstructor().newInstance().also {
            set(it, "transducer", transducer)
            set(it, "tokens", file("sherpa-stt-tokens.txt"))
            set(it, "numThreads", 2)
            set(it, "debug", false)
            set(it, "provider", "cpu")
        }
        val config = cls("OnlineRecognizerConfig").getDeclaredConstructor().newInstance().also {
            set(it, "featConfig", feat)
            set(it, "modelConfig", model)
            set(it, "endpointConfig", cls("EndpointConfig").getDeclaredConstructor().newInstance())
            set(it, "enableEndpoint", true)
            set(it, "decodingMethod", "greedy_search")
            set(it, "maxActivePaths", 4)
        }
        recognizer = recognizerClass.getConstructor(android.content.res.AssetManager::class.java, cls("OnlineRecognizerConfig"))
            .newInstance(null, config)
        stream = createStream.invoke(recognizer, "")!!
        streamClass = stream.javaClass
        accept = streamClass.getDeclaredMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
        inputFinished = streamClass.getDeclaredMethod("inputFinished")
        releaseStream = streamClass.getDeclaredMethod("release")
        isReady = recognizerClass.getDeclaredMethod("isReady", streamClass)
        decode = recognizerClass.getDeclaredMethod("decode", streamClass)
        isEndpoint = recognizerClass.getDeclaredMethod("isEndpoint", streamClass)
        getResult = recognizerClass.getDeclaredMethod("getResult", streamClass)
        getText = getResult.invoke(recognizer, stream)!!.javaClass.getDeclaredMethod("getText")
    }

    private fun text(): String = getText.invoke(getResult.invoke(recognizer, stream)).toString().trim()

    override fun reset() {
        releaseStream.invoke(stream)
        stream = createStream.invoke(recognizer, "")!!
        lastText = ""
        endpointsBeforeEof = 0
    }

    override fun feed(chunk: FloatArray): String {
        accept.invoke(stream, chunk, 16_000)
        while (isReady.invoke(recognizer, stream) as Boolean) decode.invoke(recognizer, stream)
        lastText = text()
        if (lastText.isNotEmpty() && isEndpoint.invoke(recognizer, stream) as Boolean) endpointsBeforeEof++
        return lastText
    }

    override fun correctedPartial(): String = lastText

    override fun finish(): Pair<String, String> {
        inputFinished.invoke(stream)
        while (isReady.invoke(recognizer, stream) as Boolean) decode.invoke(recognizer, stream)
        val t = text()
        return t to t
    }

    /** `SherpaOnnxVoiceInputController.resultTextOnline` pre-processing before the normaliser. */
    override fun shippingPreNormalise(final: String): String =
        final.trim().trimEnd('?', '.', '!', ',', ':', ';').lowercase(Locale.ROOT)

    override fun close() {
        runCatching { releaseStream.invoke(stream) }
        recognizerClass.getDeclaredMethod("release").invoke(recognizer)
    }
}
