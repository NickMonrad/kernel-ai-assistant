package com.kernel.ai.modelbakeoff

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.inference.BackendType
import com.kernel.ai.core.inference.toBackend
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Explicit one-model/one-backend run; this class is never selected by the normal device suite. */
@OptIn(ExperimentalApi::class)
class ModelBakeoffDeviceTest {
    private data class ModelSpec(val fileName: String, val revision: String, val bytes: Long, val sha256: String, val license: String)
    private data class Held(val id: String, val description: String, val status: String, val slots: List<String> = emptyList())
    private data class TransitionFixture(
        val name: String,
        val held: List<Held>,
        val context: String?,
        val reply: String,
        val transition: String,
        val actionId: String?,
        val slotName: String? = null,
        val slotValue: String? = null,
        val dispatchTarget: String? = null,
    )
    private data class NumericFixture(val id: String, val grounding: String, val prompt: String, val expected: List<String>, val forbidden: List<String> = emptyList())
    private data class RunResult(val output: String, val ttftMs: Long, val elapsedMs: Long, val pssKb: Long, val gpuKb: Long?, val initMs: Long, val decodeTps: Double?, val mtpActive: Boolean)
    private data class FixtureTool(val name: String, val provider: ToolProvider, val execute: (String) -> String)
    private data class TextToolCall(val name: String, val argumentsJson: String)

    @Test
    fun runPinnedBakeoffCorpus(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        assertEquals("com.kernel.ai.debug.modelbakeoff", context.packageName)
        val args = InstrumentationRegistry.getArguments()
        val model = requireNotNull(args.getString("model")) { "Pass model=gemma|qwen|agents|nemotron" }
        val backendName = requireNotNull(args.getString("backend")) { "Pass backend=GPU|CPU" }
        val mtp = args.getString("mtp")?.toBooleanStrictOrNull() ?: false
        val expectedModels = setOf("gemma", "qwen", "agents", "nemotron")
        require(model in expectedModels) { "Unknown model=$model" }
        require(backendName in setOf("GPU", "CPU")) { "Unknown backend=$backendName" }
        val spec = modelSpec(model)
        val file = File(requireNotNull(context.getExternalFilesDir("models")), spec.fileName)
        require(file.isFile) { "MODEL_FILE_MISSING ${file.absolutePath}" }
        require(file.length() == spec.bytes) { "MODEL_FILE_SIZE_MISMATCH bytes=${file.length()} expected=${spec.bytes}" }
        val actualSha256 = withContext(Dispatchers.IO) { sha256(file) }
        assertEquals("Model file SHA256 mismatch", spec.sha256, actualSha256)
        val backendType = BackendType.valueOf(backendName)
        val backend = backendType.toBackend(context)
        Log.i(TAG, "MODEL_PIN model=$model repoRevision=${spec.revision} bytes=${spec.bytes} sha256=$actualSha256 license=${spec.license} sdk=0.17.1")
        Log.i(TAG, "RUN_START model=$model backend=$backendName mtp=$mtp")

        val allResults = mutableListOf<String>()
        var structuralPasses = 0
        var semanticPasses = 0
        var expectedDispatches = 0
        var dispatchPasses = 0
        var unexpectedDispatches = 0
        var numericPasses = 0
        var toolPasses = 0
        val peakPss = AtomicLong(0L)
        val peakGpuKb = AtomicLong(0L)
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakPss.accumulateAndGet(Debug.getPss().toLong()) { old, new -> maxOf(old, new) }
                readGpuMemoryKb()?.let { peakGpuKb.accumulateAndGet(it) { old, new -> maxOf(old, new) } }
                delay(75)
            }
        }
        try {
            val transitions = transitionFixtures()
            transitions.forEach { fixture ->
                val prompt = transitionPrompt(fixture)
                val schema = transitionSchema(fixture.held)
                val run = runOne(context, file, backend, mtp, TRANSITION_SYSTEM, prompt, schema)
                val parsed = runCatching { JSONObject(run.output) }.getOrNull()
                val structural = parsed != null && parsed.keys().asSequence().toSet() == TRANSITION_KEYS.toSet() &&
                    parsed.optString("transition") in TRANSITIONS &&
                    (parsed.isNull("action_id") || fixture.held.any { it.id == parsed.optString("action_id") }) &&
                    (parsed.isNull("slot_name") || fixture.held.any { parsed.optString("slot_name") in it.slots }) &&
                    (parsed.isNull("slot_value") || parsed.opt("slot_value") is String)
                val transitionCorrect = structural && parsed!!.optString("transition") == fixture.transition &&
                    nullableString(parsed, "action_id") == fixture.actionId
                val slotCorrect = structural && nullableString(parsed!!, "slot_name") == fixture.slotName &&
                    normalize(nullableString(parsed, "slot_value")) == normalize(fixture.slotValue)
                val proposalDispatch = if (structural && parsed!!.optString("transition") == "CONTINUE") nullableString(parsed, "action_id") else null
                val dispatchCorrect = if (fixture.dispatchTarget == null) proposalDispatch == null
                    else proposalDispatch == fixture.dispatchTarget && slotCorrect
                if (structural) structuralPasses++
                if (transitionCorrect) semanticPasses++
                if (fixture.dispatchTarget != null) {
                    expectedDispatches++
                    if (dispatchCorrect) dispatchPasses++
                } else if (proposalDispatch != null) {
                    unexpectedDispatches++
                }
                val line = "TRANSITION model=$model backend=$backendName mtp=$mtp mtpActive=${run.mtpActive} fixture=${fixture.name} structural=$structural semantic=$transitionCorrect slot=$slotCorrect proposalDispatch=$proposalDispatch expectedDispatch=${fixture.dispatchTarget} dispatchCorrect=$dispatchCorrect initMs=${run.initMs} ttftMs=${run.ttftMs} elapsedMs=${run.elapsedMs} decodeTps=${run.decodeTps ?: "unavailable"} pssKb=${run.pssKb} gpuKb=${run.gpuKb ?: "unavailable"} output=${run.output.take(280).replace('\n', ' ')}"
                Log.i(TAG, line)
                allResults += line
            }

            numericFixtures().forEach { fixture ->
                val run = runOne(context, file, backend, mtp, NUMERIC_SYSTEM, fixture.prompt, schema = null)
                val present = fixture.expected.all { numericContains(run.output, it) }
                val forbiddenClear = fixture.forbidden.none { numericContains(run.output, it) }
                if (present && forbiddenClear) numericPasses++
                val line = "NUMERIC model=$model backend=$backendName mtp=$mtp mtpActive=${run.mtpActive} fixture=${fixture.id} grounding=${fixture.grounding} expected=${fixture.expected.joinToString("|")} forbidden=${fixture.forbidden.joinToString("|")} exactCopy=$present forbiddenClear=$forbiddenClear numericPass=${present && forbiddenClear} initMs=${run.initMs} ttftMs=${run.ttftMs} elapsedMs=${run.elapsedMs} decodeTps=${run.decodeTps ?: "unavailable"} pssKb=${run.pssKb} gpuKb=${run.gpuKb ?: "unavailable"} output=${run.output.take(350).replace('\n', ' ')}"
                allResults += line
            }

            toolTranscripts(context, file, backend, mtp).forEach { line ->
                if (line.contains("expectedCallObserved=true")) toolPasses++
                Log.i(TAG, line)
                allResults += line
            }
            val qualitySummary = "QUALITY_SUMMARY model=$model backend=$backendName mtp=$mtp transitionStructural=$structuralPasses/${transitions.size} transitionSemantic=$semanticPasses/${transitions.size} dispatchCorrect=$dispatchPasses/$expectedDispatches unexpectedDispatches=$unexpectedDispatches numericExact=$numericPasses/${numericFixtures().size} toolSequences=$toolPasses/5"
            Log.i(TAG, qualitySummary)

            val warmReload = runOne(context, file, backend, mtp, "Reply only READY.", "Say READY.", schema = null)
            val normalChatHealthy = warmReload.output.contains("READY", ignoreCase = true)
            val cancellationOutcome = runCatching {
                cancellationAndRecovery(context, file, backend, mtp)
            }
            val cancellation = cancellationOutcome.getOrElse { -1L to false }
            val cancellationError = cancellationOutcome.exceptionOrNull()?.javaClass?.simpleName ?: "none"
            val lifecycleLine =
                "LIFECYCLE model=$model backend=$backendName mtp=$mtp warmReloadInitMs=${warmReload.initMs} " +
                    "warmReloadTtftMs=${warmReload.ttftMs} normalChatHealth=$normalChatHealthy " +
                    "cancellationDrainMs=${cancellation.first} cancellationRecovery=${cancellation.second} " +
                    "cancellationError=$cancellationError peakPssKb=${peakPss.get()} " +
                    "peakGpuKb=${peakGpuKb.get().takeIf { it > 0L } ?: "unavailable"}"
            Log.i(TAG, lifecycleLine)
            val reportFile = File(context.filesDir, "J691Bakeoff/results/${model}_${backendName.lowercase()}_mtp-$mtp.txt")
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(
                qualitySummary + "\n" +
                    allResults.joinToString(separator = "\n", postfix = "\n") +
                    lifecycleLine + "\n",
            )
            Log.i(TAG, "LOCAL_REPORT path=${reportFile.absolutePath}")
            require(allResults.size == transitions.size + numericFixtures().size + 5) { "Incomplete evidence rows: ${allResults.size}" }
            require(normalChatHealthy) { "Warm-reload normal chat did not return READY: ${warmReload.output.take(200)}" }
            require(cancellation.second) { "Cancellation recovery did not return READY: $cancellationError" }
            Log.i(TAG, "RUN_COMPLETE model=$model backend=$backendName mtp=$mtp transitionRows=${transitions.size} numericRows=${numericFixtures().size} toolRows=5 peakPssKb=${peakPss.get()} peakGpuKb=${peakGpuKb.get().takeIf { it > 0L } ?: "unavailable"}")
        } finally {
            sampler.cancelAndJoin()
        }
    }

    private suspend fun runOne(
        context: Context,
        file: File,
        backend: Backend,
        mtp: Boolean,
        system: String,
        prompt: String,
        schema: String? = null,
        tools: List<FixtureTool> = emptyList(),
    ): RunResult = withContext(Dispatchers.IO) {
        val capabilities = runCatching { Capabilities(file.absolutePath).use { it.hasSpeculativeDecodingSupport() } }.getOrDefault(false)
        val mtpActive = mtp && capabilities
        val engineConfig = EngineConfig(
            modelPath = file.absolutePath,
            backend = backend,
            maxNumTokens = 2048,
            cacheDir = context.cacheDir.absolutePath,
        )
        val textToolCalls = modelFamily(file.name) in setOf("qwen", "agents")
        var engine: Engine? = null
        var conversation: Conversation? = null
        try {
            ExperimentalFlags.enableBenchmark = true
            ExperimentalFlags.enableSpeculativeDecoding = mtpActive
            ExperimentalFlags.enableConversationConstrainedDecoding =
                schema != null || (tools.isNotEmpty() && !textToolCalls)
            val initStart = SystemClock.elapsedRealtime()
            val created = Engine(engineConfig)
            engine = created
            created.initialize()
            val initMs = SystemClock.elapsedRealtime() - initStart
            val config = ConversationConfig(
                systemInstruction = Contents.of(Content.Text(system)),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 42),
                tools = tools.map { it.provider },
                automaticToolCalling = tools.isNotEmpty() && !textToolCalls,
                thinkingConfig = ThinkingConfig(enableThinking = false),
                enableResponseFormat = schema != null,
                maxOutputToken = 256,
            )
            val conv = created.createConversation(config)
            conversation = conv
            val start = SystemClock.elapsedRealtime()
            val first = AtomicLong(-1L)
            val output = StringBuilder()

            suspend fun send(contents: Contents? = null, toolResponse: Message? = null): String {
                val turnOutput = StringBuilder()
                val done = CompletableDeferred<Unit>()
                val callback = object : MessageCallback {
                    override fun onMessage(message: Message) {
                        first.compareAndSet(-1L, SystemClock.elapsedRealtime())
                        turnOutput.append(message.toString())
                    }
                    override fun onDone() { done.complete(Unit) }
                    override fun onError(throwable: Throwable) { done.completeExceptionally(throwable) }
                }
                if (toolResponse == null) {
                    conv.sendMessageAsync(
                        requireNotNull(contents),
                        callback,
                        thinkingConfig = ThinkingConfig(enableThinking = false),
                        responseFormat = schema?.let(ResponseFormat::json),
                    )
                } else {
                    conv.sendMessageAsync(toolResponse, callback, emptyMap())
                }
                withTimeout(120_000) { done.await() }
                val text = turnOutput.toString().trim()
                output.append(text).append('\n')
                return text
            }

            var current = send(contents = Contents.of(Content.Text(prompt)))
            var dispatched = 0
            while (textToolCalls && dispatched < tools.size + 1) {
                val call = parseNativeTextToolCall(current) ?: break
                val tool = tools.firstOrNull { it.name == call.name }
                if (tool == null) {
                    output.append("UNKNOWN_TEXT_TOOL name=${call.name}\n")
                    break
                }
                val execution = runCatching { tool.execute(call.argumentsJson) }
                if (execution.isFailure) {
                    output.append("TEXT_TOOL_EXECUTION_FAILED name=${tool.name} error=${execution.exceptionOrNull()?.javaClass?.simpleName}\n")
                    break
                }
                val result = execution.getOrThrow()
                current = send(toolResponse = Message.tool(Contents.of(Content.ToolResponse(tool.name, result))))
                dispatched++
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            val ttft = if (first.get() < 0) elapsed else first.get() - start
            val info = runCatching { conv.getBenchmarkInfo() }.getOrNull()
            val decode = info?.lastDecodeTokensPerSecond?.takeIf { it > 0.0 }
            RunResult(output.toString().trim(), ttft, elapsed, Debug.getPss().toLong(), readGpuMemoryKb(), initMs, decode, mtpActive)
        } finally {
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            ExperimentalFlags.enableSpeculativeDecoding = false
            ExperimentalFlags.enableBenchmark = false
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    private suspend fun cancellationAndRecovery(context: Context, file: File, backend: Backend, mtp: Boolean): Pair<Long, Boolean> = coroutineScope {
        val capabilities = runCatching { Capabilities(file.absolutePath).use { it.hasSpeculativeDecodingSupport() } }.getOrDefault(false)
        val engine = Engine(EngineConfig(file.absolutePath, backend, maxNumTokens = 2048, cacheDir = context.cacheDir.absolutePath))
        ExperimentalFlags.enableSpeculativeDecoding = mtp && capabilities
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableConversationConstrainedDecoding = true
        try {
            engine.initialize()
            val conversation = engine.createConversation(ConversationConfig(
                systemInstruction = Contents.of(Content.Text("Return a JSON object with one string field named value containing at least 2048 characters.")),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 42),
                automaticToolCalling = false,
                enableResponseFormat = true,
                maxOutputToken = 2048,
            ))
            val schema = """{"type":"object","additionalProperties":false,"required":["value"],"properties":{"value":{"type":"string","minLength":2048,"maxLength":2048}}}"""
            val started = SystemClock.elapsedRealtime()
            val generation = async(Dispatchers.IO) {
                val done = CompletableDeferred<Unit>()
                conversation.sendMessageAsync(
                    Contents.of(Content.Text("Produce the requested value now.")),
                    object : MessageCallback {
                        override fun onMessage(message: Message) = Unit
                        override fun onDone() { done.complete(Unit) }
                        override fun onError(throwable: Throwable) { done.completeExceptionally(throwable) }
                    },
                    thinkingConfig = ThinkingConfig(enableThinking = false),
                    responseFormat = ResponseFormat.json(schema),
                )
                done.await()
            }
            delay(300)
            conversation.cancelProcess()
            generation.cancelAndJoin()
            val drain = SystemClock.elapsedRealtime() - started
            conversation.close()
            val recovered = runCatching {
                val next = engine.createConversation(ConversationConfig(systemInstruction = Contents.of(Content.Text("Return only READY.")), samplerConfig = SamplerConfig(1, 1.0, 0.0), automaticToolCalling = false))
                val done = CompletableDeferred<String>()
                next.sendMessageAsync(Contents.of(Content.Text("Say READY.")), object : MessageCallback {
                    override fun onMessage(message: Message) { if (!done.isCompleted) done.complete(message.toString()) }
                    override fun onDone() { if (!done.isCompleted) done.complete("") }
                    override fun onError(throwable: Throwable) { done.completeExceptionally(throwable) }
                })
                val answer = withTimeout(120_000) { done.await() }
                next.close()
                answer.contains("READY", ignoreCase = true)
            }.getOrDefault(false)
            drain to recovered
        } finally {
            runCatching { engine.close() }
            ExperimentalFlags.enableSpeculativeDecoding = false
            ExperimentalFlags.enableBenchmark = false
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    private suspend fun toolTranscripts(context: Context, file: File, backend: Backend, mtp: Boolean): List<String> {
        val cases = listOf(
            "query_wikipedia_natural" to "Look up the history of the Battle of Hastings on Wikipedia for me",
            "save_memory_durable_fact" to "Here is a lasting fact I want you to know: my preferred dry cleaner is Star Dry Cleaning",
            "run_intent_get_stopwatch_status_direct" to "Please check whether the device stopwatch is currently running and report its status without starting, pausing, or resetting it. Use the read-only run_intent get_stopwatch_status action with empty parameters.",
            "run_intent_get_stopwatch_status_after_skill_load" to "Please read the run_intent instructions first, then check whether the device stopwatch is running and report its status. Do not start, pause, or reset it.",
            "get_system_info_natural" to "Can you inspect this device and summarise its current system status?",
        )
        val expectedSequences = listOf(
            listOf("query_wikipedia"),
            listOf("save_memory"),
            listOf("run_intent"),
            listOf("load_skill", "run_intent"),
            listOf("get_system_info"),
        )
        return cases.mapIndexed { index, (case, prompt) ->
            val calls = mutableListOf<String>()
            val toolNames = expectedSequences[index].distinct()
            val tools = toolNames.map { name ->
                when (name) {
                    "query_wikipedia" -> toolProvider(name, "Look up a topic on Wikipedia.", """{"type":"object","required":["query"],"additionalProperties":false,"properties":{"query":{"type":"string"}}}""", calls) {
                        """{"title":"Battle of Hastings","snippet":"The Battle of Hastings was fought on 14 October 1066."}"""
                    }
                    "save_memory" -> toolProvider(name, "Save a durable user fact to local memory.", """{"type":"object","required":["content"],"additionalProperties":false,"properties":{"content":{"type":"string"}}}""", calls) {
                        """{"saved":true,"content":"The user's preferred dry cleaner is Star Dry Cleaning."}"""
                    }
                    "run_intent" -> toolProvider(name, "Execute the specified safe read-only device intent.", """{"type":"object","required":["intent_name","parameters"],"additionalProperties":false,"properties":{"intent_name":{"type":"string","enum":["get_stopwatch_status"]},"parameters":{"type":"string"}}}""", calls) {
                        """{"status":"Stopwatch is not running"}"""
                    }
                    "load_skill" -> toolProvider(name, "Load skill instructions by skill name.", """{"type":"object","required":["skill_name"],"additionalProperties":false,"properties":{"skill_name":{"type":"string","enum":["run_intent"]}}}""", calls) {
                        """{"instructions":"Use run_intent with intent_name get_stopwatch_status and parameters {}. Read-only; do not change stopwatch state."}"""
                    }
                    else -> toolProvider(name, "Return test-only device system status.", """{"type":"object","additionalProperties":false,"properties":{}}""", calls) {
                        """{"device":"S23 Ultra test probe","status":"ready"}"""
                    }
                }
            }
            val run = runOne(context, file, backend, mtp, TOOL_SYSTEM, prompt, schema = null, tools = tools)
            val callEvents = calls.filter { it.startsWith("call:") }
            val callNames = callEvents.map { it.substringAfter("call:").substringBefore(':') }
            val toolResultCount = calls.count { it.startsWith("result:") }
            val argumentsCorrect = toolArgumentsMatch(case, callEvents)
            val expectedObserved = callNames == expectedSequences[index] &&
                toolResultCount == expectedSequences[index].size && argumentsCorrect && run.output.isNotBlank()
            val transcriptFile = File(context.filesDir, "J691Bakeoff/transcripts/${modelFamily(file.name)}_${backend.name}_mtp-${mtp}_${case}.txt")
            check(transcriptFile.parentFile?.mkdirs() == true || transcriptFile.parentFile?.isDirectory == true)
            transcriptFile.writeText(
                "model=${file.name}\nbackend=${backend.name}\nmtp=$mtp\nsystem=$TOOL_SYSTEM\nuser=$prompt\n" +
                    "orderedToolEvents:\n${calls.joinToString("\n")}\nassistantTurns:\n${run.output}\n",
            )
            val textToolCalls = modelFamily(file.name) in setOf("qwen", "agents")
            val format = when (modelFamily(file.name)) {
                "gemma" -> "Gemma native function calls"
                "nemotron" -> "Gemma-derived function calls"
                "agents" -> "Agents-A1 native XML tool calls"
                else -> "Qwen3.5 native XML tool calls"
            }
            val executionPath = if (textToolCalls) "canonical-text-XML-adapter" else "LiteRT-LM-native-tool-calls"
            "TOOL_TRANSCRIPT model=${file.name} backend=${backend.name} mtp=$mtp mtpActive=${run.mtpActive} canonicalFormat=$format executionPath=$executionPath case=$case calls=${callNames.joinToString("->")} expectedCalls=${expectedSequences[index].joinToString("->")} argsCorrect=$argumentsCorrect expectedCallObserved=$expectedObserved toolResults=$toolResultCount fixtureToolResponses=local-stub initMs=${run.initMs} ttftMs=${run.ttftMs} elapsedMs=${run.elapsedMs} decodeTps=${run.decodeTps ?: "unavailable"} pssKb=${run.pssKb} gpuKb=${run.gpuKb ?: "unavailable"} transcriptPath=${transcriptFile.absolutePath} assistant=${run.output.take(350).replace('\n', ' ')}"
        }
    }

    private fun toolProvider(
        name: String,
        description: String,
        parameters: String,
        calls: MutableList<String>,
        response: (JSONObject) -> String,
    ): FixtureTool {
        val implementation = object : OpenApiTool {
            override fun getToolDescriptionJsonString(): String = JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", JSONObject(parameters))
                .toString()

            override fun execute(paramsJsonString: String): String {
                val params = JSONObject(paramsJsonString)
                calls += "call:$name:${params}"
                val result = response(params)
                calls += "result:$name:$result"
                return result
            }
        }
        return FixtureTool(name, tool(implementation), implementation::execute)
    }

    private fun parseNativeTextToolCall(output: String): TextToolCall? {
        val call = Regex("(?s)<tool_call>\\s*<function=([^>\\s]+)>\\s*(.*?)</function>\\s*</tool_call>")
            .find(output) ?: return null
        val arguments = JSONObject()
        Regex("(?s)<parameter=([^>\\s]+)>\\s*(.*?)\\s*</parameter>")
            .findAll(call.groupValues[2])
            .forEach { match -> arguments.put(match.groupValues[1], match.groupValues[2].trim()) }
        return TextToolCall(call.groupValues[1], arguments.toString())
    }

    private fun toolArgumentsMatch(case: String, events: List<String>): Boolean {
        val parsed = events.mapNotNull { event ->
            val payload = event.removePrefix("call:")
            val name = payload.substringBefore(':')
            val arguments = runCatching { JSONObject(payload.substringAfter(':', "")) }.getOrNull()
            arguments?.let { name to it }
        }
        if (parsed.size != events.size) return false
        fun arguments(name: String) = parsed.firstOrNull { it.first == name }?.second
        fun stopwatchArgsAreValid(): Boolean {
            val runIntent = arguments("run_intent") ?: return false
            val parameters = runIntent.optString("parameters")
            return runIntent.optString("intent_name") == "get_stopwatch_status" &&
                (parameters.isBlank() || runCatching { JSONObject(parameters).length() == 0 }.getOrDefault(false))
        }
        return when (case) {
            "query_wikipedia_natural" ->
                arguments("query_wikipedia")?.optString("query").orEmpty().contains("battle of hastings", ignoreCase = true)
            "save_memory_durable_fact" ->
                arguments("save_memory")?.optString("content").orEmpty()
                    .contains("preferred dry cleaner is Star Dry Cleaning", ignoreCase = true)
            "run_intent_get_stopwatch_status_direct" -> stopwatchArgsAreValid()
            "run_intent_get_stopwatch_status_after_skill_load" ->
                arguments("load_skill")?.optString("skill_name") == "run_intent" && stopwatchArgsAreValid()
            "get_system_info_natural" -> arguments("get_system_info")?.length() == 0
            else -> false
        }
    }

    private fun transitionFixtures(): List<TransitionFixture> {
        val wiki = Held("wiki-check-1", "Wikipedia fact-check of the claim that prisoners brew alcohol in toilets", "pending")
        val weather = Held("weather-1", "weather lookup for Sydney, today", "completed", listOf("date"))
        val reminder = Held("reminder-1", "reminder to call the blinds people on Monday; waiting for the time", "pending", listOf("time"))
        val timer42 = Held("timer-42", "timer for 20 minutes", "active", listOf("duration_minutes"))
        val timer43 = Held("timer-43", "timer for 25 minutes", "active", listOf("duration_minutes"))
        val reminderPrompt = "Assistant's last message: What time on Monday should I remind you?"
        val weatherPrompt = "Assistant's last message: It is sunny in Sydney today."
        return listOf(
            TransitionFixture("wikipedia-disputed-claim-continuation", listOf(wiki, weather), "Assistant's last message: Which claim should I fact-check?", "The topic we have just been talking about.", "CONTINUE", "wiki-check-1", dispatchTarget = "wiki-check-1"),
            TransitionFixture("reminder-cancel", listOf(reminder, timer42), reminderPrompt, "cancel", "CANCEL", "reminder-1"),
            TransitionFixture("pending-reminder-weather-replacement", listOf(reminder), reminderPrompt, "What's the weather in Sydney?", "REPLACE", "reminder-1"),
            TransitionFixture("weather-tomorrow-keeps-sydney", listOf(weather), weatherPrompt, "What about tomorrow?", "CONTINUE", "weather-1", "date", "tomorrow", "weather-1"),
            TransitionFixture("timer-correction-same-object", listOf(timer42), "Assistant's last message: Timer set for 20 minutes.", "Actually make that 15.", "CONTINUE", "timer-42", "duration_minutes", "15", "timer-42"),
            TransitionFixture("ambiguous-two-timer-target", listOf(timer42, timer43), null, "Make that one 15.", "CLARIFY", null),
            TransitionFixture("genuine-topic-change-pending-slot", listOf(reminder), reminderPrompt, "Set a timer for 20 minutes.", "REPLACE", "reminder-1"),
            TransitionFixture("ambiguous-reference-clarifies", listOf(Held("timer-51", "timer for 10 minutes", "active", listOf("duration_minutes")), Held("timer-52", "timer for 30 minutes", "active", listOf("duration_minutes"))), null, "Change that.", "CLARIFY", null),
            TransitionFixture("unrelated-request-after-completed-lookup", listOf(weather), weatherPrompt, "Set a timer for 10 minutes.", "NEW_TOPIC", null),
        )
    }

    private fun numericFixtures() = listOf(
        NumericFixture("battery-76-percent", "user fact", "The phone battery reads 76%. Repeat the exact battery level.", listOf("76%"), listOf("6%", "7%")),
        NumericFixture("battery-76-percent-paraphrase", "user fact", "Current charge: 76 percent (76%). What percent battery remains? Give the exact percent.", listOf("76%"), listOf("6%", "7%")),
        NumericFixture("date-april-15", "user fact", "The date is April 15, 2026. Repeat the date exactly.", listOf("April 15, 2026"), listOf("April 5, 2026")),
        NumericFixture("alarm-shorthand-630", "user request", "Wake me up at 630. What alarm time should be used? Reply HH:MM.", listOf("06:30"), listOf("6:300")),
        NumericFixture("alarm-shorthand-930", "user request", "Wake me up at 930. What alarm time should be used? Reply HH:MM.", listOf("09:30"), listOf("9:300")),
        NumericFixture("percentage-92-versus-9", "retrieved context", "Retrieved report: completion was 92%; a different section reports 9%. What was the completion rate?", listOf("92%"), listOf("9%")),
        NumericFixture("percentages-0.9-and-99.9", "retrieved context", "Retrieved report: measured error 0.9%; alert threshold 99.9%. State the measured error.", listOf("0.9%"), listOf("9%", "99.9%")),
        NumericFixture("nearby-years-2007-2009", "retrieved context", "Retrieved chronology: event A happened in 2007 and event B in 2009. Give both years in order.", listOf("2007", "2009"), listOf("200007", "209")),
        NumericFixture("nearby-years-2024-2026", "local memory", "Saved fact: the old benchmark was 2024 and the new benchmark is 2026. State the new benchmark year.", listOf("2026"), listOf("2024")),
        NumericFixture("large-population", "tool-result-shaped context", "Tool result: {\"population\":1234567,\"nearby_city\":123456}. What is the population? Preserve the digit string exactly.", listOf("1234567"), listOf("123456", "1,234,567")),
        NumericFixture("large-distance-and-file-size", "retrieved context", "Reference: distance 12,345 km; file size 4,274,913,280 bytes. Report the exact file size.", listOf("4,274,913,280"), listOf("274,913,280", "4,274,913,28")),
        NumericFixture("decimal-0.25", "local memory", "Saved measurement: 0.25. Repeat it exactly.", listOf("0.25"), listOf("0.2", "0.5")),
        NumericFixture("decimal-3.14-and-12.5", "retrieved context", "Retrieved measurements: 3.14 units and 12.5 units. State both exactly.", listOf("3.14", "12.5"), listOf("3.1", "12.05")),
        NumericFixture("currency-1200", "user fact", "The invoice total is \$1,200. Repeat the total exactly.", listOf("\$1,200"), listOf("\$200", "\$1.200")),
        NumericFixture("currency-12.50-and-AUD-30k", "tool-result-shaped context", "Tool result: budget=\$12.50; grant=A\$30k. Report the grant amount exactly.", listOf("A\$30k"), listOf("\$30k", "A\$3k")),
        NumericFixture("ranges-years-duration", "retrieved context", "Retrieved plan: phase 2007–2009; wait 5–10 minutes; delivery in 2–3 days. Repeat all three ranges.", listOf("2007–2009", "5–10 minutes", "2–3 days")),
        NumericFixture("units-mixed", "tool-result-shaped context", "Tool result: storage=100 GB; archive=10 TB; signal=16 kHz; latency=80 ms. Repeat all four values with units.", listOf("100 GB", "10 TB", "16 kHz", "80 ms")),
        NumericFixture("date-text-and-iso", "retrieved context", "Retrieved event date: 4 June 2026 (2026-06-04). Give both date forms exactly.", listOf("4 June 2026", "2026-06-04")),
        NumericFixture("noisy-context-nearby-decimals", "retrieved context", "Retrieved values: current=12.5; previous=12.05; budget=\$12.50; distance=16 kHz. Report current, budget, and distance only.", listOf("12.5", "\$12.50", "16 kHz"), listOf("12.05")),
        NumericFixture("concise-single-number", "concise context", "Answer from this fact only: calibration offset is 0.25.", listOf("0.25")),
    )

    private fun transitionPrompt(fixture: TransitionFixture) = buildString {
        appendLine("Held actions (most recent first):")
        fixture.held.forEach { held ->
            val slots = if (held.slots.isEmpty()) "" else " (slots: ${held.slots.joinToString()})"
            appendLine("- id ${held.id} [${held.status}]: ${held.description}$slots")
        }
        fixture.context?.let { appendLine(it) }
        append("User reply: ").append(fixture.reply)
    }

    private fun transitionSchema(held: List<Held>): String {
        fun nullableEnum(values: List<String>) = JSONObject().put("enum", JSONArray().apply { values.forEach(::put); put(JSONObject.NULL) })
        val properties = JSONObject()
            .put("transition", JSONObject().put("type", "string").put("enum", JSONArray(TRANSITIONS)))
            .put("action_id", nullableEnum(held.map { it.id }))
            .put("slot_name", nullableEnum(held.flatMap { it.slots }.distinct()))
            .put("slot_value", JSONObject().put("type", JSONArray(listOf("string", "null"))))
        return JSONObject().put("type", "object").put("additionalProperties", false)
            .put("required", JSONArray(TRANSITION_KEYS)).put("properties", properties).toString()
    }

    private fun nullableString(json: JSONObject, key: String): String? = if (json.isNull(key)) null else json.optString(key)
    private fun normalize(value: String?) = value?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")
    private fun numericContains(output: String, value: String): Boolean =
        Regex(
            "(?<![\\p{L}\\p{N}.,])${Regex.escape(value)}(?![\\p{L}\\p{N}]|[.,](?=[\\p{L}\\p{N}]))",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(output)
    private fun modelSpec(model: String): ModelSpec = when (model) {
        "gemma" -> ModelSpec("gemma-4-E4B-it.litertlm", "e6a3892a8c22cf87ff99583996149cf3cb32452b", 3_659_530_240L, "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", "Apache-2.0")
        "qwen" -> ModelSpec("Qwen3.5-4B_mixed_int4.litertlm", "3e4011ec2e502694f4523ac12f6348eeb21ad4ab", 2_754_070_624L, "7243c33c9c6192150ab6da7ac13a9efdc24ff1a653eabc93b2757de4b44b012a", "Apache-2.0")
        "agents" -> ModelSpec("Agents-A1-4B_mixed_int4.litertlm", "6c44e56f51b2d418cd3b8266bf7633cc96535e83", 2_754_365_536L, "13c53e3ada0655ed1af4204aa2b659fe7cd553a1e7d5ef8e8b11d1ed3c15203c", "Apache-2.0")
        "nemotron" -> ModelSpec("NVIDIA-Nemotron-3.5-Bolt-E4B_int4.litertlm", "a98def827e7f80e991aa894d2c91d2ec7e865784", 4_404_768_064L, "e24456d06bd9155470593f036d4b63e5b47d53ae7e88d8511c6ba768c6e68d6b", "OpenMDW-1.1")
        else -> error("Unknown model=$model")
    }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    private fun modelFamily(name: String) = when {
        name.contains("Agents") -> "agents"
        name.contains("Nemotron") -> "nemotron"
        name.contains("Qwen") -> "qwen"
        else -> "gemma"
    }

    private fun readGpuMemoryKb(): Long? = runCatching {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        memoryInfo.getMemoryStats()["summary.graphics"]?.toLongOrNull()?.takeIf { it > 0L }
            ?: listOf("/sys/class/kgsl/kgsl-3d0/gpu_memory", "/sys/class/kgsl/kgsl-3d0/memstore/gpu_memory").firstNotNullOfOrNull { path ->
                File(path).takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()?.let { it / 1024L }
            }
    }.getOrNull()

    private companion object {
        const val TAG = "J691Bakeoff"
        val TRANSITIONS = listOf("CONTINUE", "CANCEL", "REPLACE", "NEW_TOPIC", "CLARIFY")
        val TRANSITION_KEYS = listOf("transition", "action_id", "slot_name", "slot_value")
        val TRANSITION_SYSTEM = """
            Decide how the user's reply relates to the app's held actions. Return only the structured decision.
            transition:
            - CONTINUE: the reply continues or modifies one held action; action_id is that action.
            - CANCEL: the reply cancels one pending action; action_id is that action.
            - REPLACE: the reply is a different request that supersedes a pending action; action_id is the superseded pending action.
            - NEW_TOPIC: the reply is an unrelated request and no pending action is superseded; action_id is null.
            - CLARIFY: it is unclear which held action is meant or what the user wants; action_id is the single action concerned, or null if more than one could be meant.
            slot_name and slot_value: set only when the user supplies a new value for a listed slot of the chosen action; slot_value is the user's value only. Otherwise both are null.
        """.trimIndent()
        const val NUMERIC_SYSTEM = "Copy requested numeric literals exactly from the user-provided context. Do not round, normalize, omit digits, or add nearby distractor values. Answer only what is requested."
        const val TOOL_SYSTEM = "You are Jandal, an on-device assistant. Use the provided tools when requested. Preserve the exact requested subject and do not invent results. For a skill discovery request, load the relevant skill, then call its terminal action."
    }
}
