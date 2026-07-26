package com.kernel.ai.core.inference

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.inference.hardware.QuantizationVerifier
import java.io.File
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.Channel
import com.google.ai.edge.litertlm.Capabilities
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.tool
import com.google.ai.edge.litertlm.ToolProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONException
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "LiteRtInferenceEngine"
private const val SCREEN_INTERACTIVE_POLL_MS = 500L
private const val SCREEN_INTERACTIVE_TIMEOUT_MS = 10_000L
private const val GPU_INIT_TIMEOUT_MS = 60_000L
/** #1293: After this many conversation resets on GPU backend, perform a full engine
 *  shutdown + initialize instead of just recreating the LiteRT session.
 *  S21 evidence shows MiniLM classifier convergence to play_netflix after ~7
 *  resets (pre-existing, GPU-independent). Threshold 5 fires the full restart
 *  before that point to keep routing reliable. */
private const val GPU_ENGINE_RESTART_INTERVAL = 5

/**
 * Decision result from [checkGpuRestartNeeded].
 * @property shouldRestart true when a full GPU engine restart should be performed.
 * @property updatedCount the new [LiteRtInferenceEngine.gpuResetCount] value to store.
 */
internal data class GpuRestartDecision(
    val shouldRestart: Boolean,
    val updatedCount: Int,
)

/**
 * Pure function: given current state, decide whether a GPU full engine restart
 * is needed and compute the new counter value.
 *
 * - GPU backend: increments count; resets to 0 and returns shouldRestart=true at threshold.
 * - Non-GPU backend: resets count to 0, never triggers restart.
 *
 * Extracted for testability — see [LiteRtInferenceEngineGpuRestartTest].
 */
internal fun checkGpuRestartNeeded(
    gpuResetCount: Int,
    backend: BackendType,
    threshold: Int = GPU_ENGINE_RESTART_INTERVAL,
): GpuRestartDecision {
    return when (backend) {
        BackendType.GPU -> {
            val newCount = gpuResetCount + 1
            if (newCount >= threshold) {
                GpuRestartDecision(shouldRestart = true, updatedCount = 0)
            } else {
                GpuRestartDecision(shouldRestart = false, updatedCount = newCount)
            }
        }
        else -> GpuRestartDecision(shouldRestart = false, updatedCount = 0)
    }
}

private const val MIN_AVAIL_MEM_FOR_GPU_BYTES = 2L * 1024 * 1024 * 1024 // 2 GB absolute floor — catches 4-6 GB devices; 8 GB devices pass this
internal const val THINKING_CHANNEL_HEADER = "<|channel>thought"
internal const val THINKING_CLOSE_MARKER = "<channel|>"

@OptIn(ExperimentalApi::class)
internal inline fun <T> withSpeculativeDecodingEnabledForInit(enabled: Boolean, block: () -> T): T {
    ExperimentalFlags.enableSpeculativeDecoding = enabled
    return try {
        block()
    } finally {
        ExperimentalFlags.enableSpeculativeDecoding = false
    }
}

internal fun resolveSpeculativeDecodingForInit(
    requested: Boolean,
    modelPath: String,
    capabilityProbe: (String) -> Boolean = ::modelSupportsSpeculativeDecoding,
    onProbeFailure: (String, Exception) -> Unit = { _, _ -> },
): Boolean {
    if (!requested) return false
    return try {
        capabilityProbe(modelPath)
    } catch (e: Exception) {
        onProbeFailure(modelPath, e)
        false
    }
}

private fun modelSupportsSpeculativeDecoding(modelPath: String): Boolean =
    Capabilities(modelPath).use { it.hasSpeculativeDecodingSupport() }

internal suspend fun waitForInteractiveState(
    isInteractive: () -> Boolean,
    pollMs: Long = SCREEN_INTERACTIVE_POLL_MS,
    timeoutMs: Long = SCREEN_INTERACTIVE_TIMEOUT_MS,
): Boolean {
    if (isInteractive()) return true
    return try {
        withTimeout(timeoutMs) {
            while (!isInteractive()) {
                delay(pollMs)
            }
        }
        true
    } catch (_: TimeoutCancellationException) {
        false
    }
}

internal fun isValidJsonObject(raw: String): Boolean =
    try {
        JSONObject(raw)
        true
    } catch (_: JSONException) {
        false
    }

internal class JsonObjectAccumulator(
    private val validator: (String) -> Boolean = ::isValidJsonObject,
) {
    private val buffer = StringBuilder()
    private var started = false
    private var depth = 0
    private var insideString = false
    private var escaping = false
    private var completedJson: String? = null

    fun append(chunk: String): String? {
        completedJson?.let { return it }
        for (ch in chunk) {
            if (!started) {
                if (ch.isWhitespace()) continue
                if (ch != '{') continue
                started = true
                depth = 1
                buffer.append(ch)
                continue
            }

            buffer.append(ch)

            if (escaping) {
                escaping = false
                continue
            }
            if (insideString && ch == '\\') {
                escaping = true
                continue
            }
            if (ch == '"') {
                insideString = !insideString
                continue
            }
            if (insideString) continue

            when (ch) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        val candidate = buffer.toString()
                        if (validator(candidate)) {
                            completedJson = candidate
                            return candidate
                        }
                    }
                }
            }
        }
        return null
    }
}


internal data class ThinkingStreamEmission(
    val thinkingDeltas: List<String> = emptyList(),
    val responseDeltas: List<String> = emptyList(),
)

internal class ThinkingStreamStateMachine(
    private val thinkingEnabled: Boolean = true,
    private val closeMarker: String = THINKING_CLOSE_MARKER,
    private val thinkingHeader: String = THINKING_CHANNEL_HEADER,
    private val onAmbiguousProtocol: (Int) -> Unit = {},
) {
    private enum class RawMode {
        VISIBLE,
        THOUGHT,
        CHANNEL_HEADER,
        CHANNEL_THOUGHT,
        CHANNEL_VISIBLE,
    }

    private enum class MarkerType {
        THINK_OPEN,
        THINK_CLOSE,
        CHANNEL_OPEN,
        CHANNEL_CLOSE,
    }

    private data class MarkerMatch(
        val type: MarkerType,
        val start: Int,
        val endExclusive: Int,
        val complete: Boolean,
    )

    private val structuredObserved = StringBuilder()
    private val structuredPending = StringBuilder()
    private val rawObserved = StringBuilder()
    private val rawPending = StringBuilder()
    private val emittedThinking = StringBuilder()
    private val emittedStructuredThinking = StringBuilder()
    private val emittedResponse = StringBuilder()
    private var rawMode = RawMode.VISIBLE

    fun consume(channelDelta: String?, rawMessage: String): ThinkingStreamEmission {
        val thinking = mutableListOf<String>()
        val response = mutableListOf<String>()

        channelDelta
            ?.takeIf { it.isNotEmpty() }
            ?.let { channel ->
                val novel = appendNovelInput(structuredObserved, channel)
                if (novel.isNotEmpty()) {
                    structuredPending.append(novel)
                    processStructuredThinking(thinking)
                }
            }

        rawMessage
            .takeIf { it.isNotEmpty() }
            ?.let { raw ->
                val novel = appendNovelInput(rawObserved, raw)
                if (novel.isNotEmpty()) {
                    rawPending.append(novel)
                    processRaw(thinking, response)
                }
            }

        return ThinkingStreamEmission(
            thinkingDeltas = thinking,
            responseDeltas = response,
        )
    }

    /**
     * Completes the stream without turning unresolved protocol or thought
     * content into visible response text.
     */
    fun finish(): ThinkingStreamEmission {
        val thinking = mutableListOf<String>()
        val response = mutableListOf<String>()
        processRaw(thinking, response)
        processStructuredThinking(thinking)

        if (rawPending.isNotEmpty()) {
            if (rawMode == RawMode.VISIBLE && !hasTrailingProtocolPrefix(rawPending.toString())) {
                emitResponse(rawPending.toString(), response)
            } else {
                warnAmbiguous(rawPending.length)
            }
            rawPending.clear()
        }
        if (structuredPending.isNotEmpty()) {
            warnAmbiguous(structuredPending.length)
            structuredPending.clear()
        }

        return ThinkingStreamEmission(
            thinkingDeltas = thinking,
            responseDeltas = response,
        )
    }

    private fun processStructuredThinking(thinking: MutableList<String>) {
        while (structuredPending.isNotEmpty()) {
            val text = structuredPending.toString()

            when {
                text.startsWith(thinkingHeader) -> {
                    deletePrefix(structuredPending, thinkingHeader.length)
                    deleteLeadingWhitespace(structuredPending)
                    continue
                }
                text.startsWith(THINK_OPEN_MARKER) -> {
                    deletePrefix(structuredPending, THINK_OPEN_MARKER.length)
                    continue
                }
            }

            val marker = findStructuredMarker(text)
            if (marker == null) {
                emitThinking(text, thinking, structured = true)
                structuredPending.clear()
                return
            }
            if (marker.start > 0) {
                emitThinking(text.substring(0, marker.start), thinking, structured = true)
                deletePrefix(structuredPending, marker.start)
                continue
            }
            if (!marker.complete) return

            deletePrefix(structuredPending, marker.endExclusive)
            deleteLeadingWhitespace(structuredPending)
        }
    }

    private fun processRaw(
        thinking: MutableList<String>,
        response: MutableList<String>,
    ) {
        while (rawPending.isNotEmpty()) {
            val beforeLength = rawPending.length
            val beforeMode = rawMode

            when (rawMode) {
                RawMode.VISIBLE -> processVisible(thinking, response)
                RawMode.THOUGHT -> processThought(thinking)
                RawMode.CHANNEL_HEADER -> processChannelHeader()
                RawMode.CHANNEL_THOUGHT -> processChannelThought(thinking)
                RawMode.CHANNEL_VISIBLE -> processChannelVisible(response)
            }
            if (beforeLength == rawPending.length && beforeMode == rawMode) return
            if (rawPending.isNotEmpty() && rawMode == RawMode.VISIBLE) {
                val marker = findRawMarker(rawPending.toString())
                if (marker == null && hasTrailingProtocolPrefix(rawPending.toString())) return
            }
        }
    }

    private fun processVisible(
        thinking: MutableList<String>,
        response: MutableList<String>,
    ) {
        val text = rawPending.toString()
        val marker = findRawMarker(text)
        if (marker == null) {
            val stableEnd = trailingProtocolPrefixStart(text)
            if (stableEnd == 0) return
            emitResponse(text.substring(0, stableEnd ?: text.length), response)
            deletePrefix(rawPending, stableEnd ?: text.length)
            return
        }
        if (marker.start > 0) {
            emitResponse(text.substring(0, marker.start), response)
            deletePrefix(rawPending, marker.start)
            return
        }
        if (!marker.complete) return

        deletePrefix(rawPending, marker.endExclusive)
        rawMode = when (marker.type) {
            MarkerType.THINK_OPEN -> RawMode.THOUGHT
            MarkerType.CHANNEL_OPEN -> RawMode.CHANNEL_HEADER
            MarkerType.THINK_CLOSE, MarkerType.CHANNEL_CLOSE -> RawMode.VISIBLE
        }
    }

    private fun processThought(thinking: MutableList<String>) {
        val text = rawPending.toString()
        val marker = findExpectedClose(text, channel = false)
        if (marker?.complete == true) {
            emitThinking(text.substring(0, marker.start), thinking)
            deletePrefix(rawPending, marker.endExclusive)
            rawMode = RawMode.VISIBLE
            return
        }

        val stableEnd = trailingProtocolPrefixStart(text)
        if (stableEnd == 0) return
        emitThinking(text.substring(0, stableEnd ?: text.length), thinking)
        deletePrefix(rawPending, stableEnd ?: text.length)
    }

    private fun processChannelHeader() {
        val text = rawPending.toString()
        val separator = text.indexOfFirst { it.isWhitespace() }
        val close = findChannelClose(text)

        if (separator < 0) {
            val thoughtClose = close?.takeIf { it.complete && it.start == "thought".length }
            if (thoughtClose != null || text.startsWith("thought") && text.length > "thought".length) {
                deletePrefix(rawPending, "thought".length)
                rawMode = RawMode.CHANNEL_THOUGHT
            }
            return
        }

        val channelName = text.substring(0, separator)
        deletePrefix(rawPending, separator + 1)
        deleteLeadingWhitespace(rawPending)
        rawMode = if (channelName == "thought") {
            RawMode.CHANNEL_THOUGHT
        } else {
            RawMode.CHANNEL_VISIBLE
        }
    }

    private fun processChannelThought(thinking: MutableList<String>) {
        val text = rawPending.toString()
        val marker = findExpectedClose(text, channel = true)
        if (marker?.complete == true) {
            emitThinking(text.substring(0, marker.start), thinking)
            deletePrefix(rawPending, marker.endExclusive)
            rawMode = RawMode.VISIBLE
            return
        }

        val stableEnd = trailingProtocolPrefixStart(text)
        if (stableEnd == 0) return
        emitThinking(text.substring(0, stableEnd ?: text.length), thinking)
        deletePrefix(rawPending, stableEnd ?: text.length)
    }

    private fun processChannelVisible(response: MutableList<String>) {
        val text = rawPending.toString()
        val marker = findChannelClose(text)
        if (marker?.complete != true) return

        emitResponse(text.substring(0, marker.start), response)
        deletePrefix(rawPending, marker.endExclusive)
        rawMode = RawMode.VISIBLE
    }

    private fun emitThinking(
        candidate: String,
        thinking: MutableList<String>,
        structured: Boolean = false,
    ) {
        val clean = stripThinkingSyntax(candidate)
        if (clean.isEmpty()) return
        val delta = stripReplayedPrefix(
            current = clean,
            emitted = emittedThinking.toString(),
            allowOverlap = true,
            minOverlapLength = 3,
        )
        if (delta.isEmpty()) return

        emittedThinking.append(delta)
        if (structured) emittedStructuredThinking.append(delta)
        if (thinkingEnabled) thinking += delta
    }

    private fun emitResponse(
        candidate: String,
        response: MutableList<String>,
    ) {
        if (candidate.isEmpty()) return
        if (containsProtocolSyntaxOrPrefix(candidate)) {
            warnAmbiguous(candidate.length)
            return
        }
        val delta = stripReplayedPrefix(
            current = candidate,
            emitted = emittedResponse.toString(),
            allowOverlap = true,
            minOverlapLength = 3,
        )
        if (delta.isEmpty() || delta.startsWith("<ctrl")) return
        if (containsProtocolSyntaxOrPrefix(delta)) {
            warnAmbiguous(delta.length)
            return
        }
        emittedResponse.append(delta)
        response += delta
    }


    private fun appendNovelInput(
        observed: StringBuilder,
        candidate: String,
    ): String {
        val prior = observed.toString()
        val novel = stripReplayedPrefix(
            current = candidate,
            emitted = prior,
            allowOverlap = true,
            minOverlapLength = 3,
        )
        if (novel.isNotEmpty()) observed.append(novel)
        return novel
    }

    private fun findStructuredMarker(text: String): MarkerMatch? =
        findMarker(
            text = text,
            includeChannelOpen = true,
            includeChannelClose = true,
            includeThinkOpen = true,
            includeThinkClose = true,
        )

    private fun findRawMarker(text: String): MarkerMatch? =
        findMarker(
            text = text,
            includeChannelOpen = true,
            includeChannelClose = true,
            includeThinkOpen = true,
            includeThinkClose = true,
        )

    private fun findExpectedClose(text: String, channel: Boolean): MarkerMatch? =
        if (channel) findChannelClose(text) else {
            for (index in text.indices) {
                if (text.startsWith(THINK_CLOSE_MARKER, index)) {
                    return MarkerMatch(
                        type = MarkerType.THINK_CLOSE,
                        start = index,
                        endExclusive = index + THINK_CLOSE_MARKER.length,
                        complete = true,
                    )
                }
                val suffix = text.substring(index)
                if (suffix.length >= 1 && THINK_CLOSE_MARKER.startsWith(suffix)) {
                    return MarkerMatch(
                        type = MarkerType.THINK_CLOSE,
                        start = index,
                        endExclusive = text.length,
                        complete = false,
                    )
                }
            }
            null
        }

    private fun findChannelClose(text: String): MarkerMatch? {
        for (index in text.indices) {
            if (!text.startsWith(CHANNEL_CLOSE_PREFIX, index)) continue
            var end = index + CHANNEL_CLOSE_PREFIX.length
            while (end < text.length && text[end].isWhitespace()) end++
            if (end < text.length && text[end] == '>') {
                return MarkerMatch(
                    type = MarkerType.CHANNEL_CLOSE,
                    start = index,
                    endExclusive = end + 1,
                    complete = true,
                )
            }
            if (end == text.length) {
                return MarkerMatch(
                    type = MarkerType.CHANNEL_CLOSE,
                    start = index,
                    endExclusive = end,
                    complete = false,
                )
            }
        }
        return null
    }

    private fun findMarker(
        text: String,
        includeChannelOpen: Boolean,
        includeChannelClose: Boolean,
        includeThinkOpen: Boolean,
        includeThinkClose: Boolean,
    ): MarkerMatch? {
        for (index in text.indices) {
            if (includeThinkOpen && text.startsWith(THINK_OPEN_MARKER, index)) {
                return MarkerMatch(MarkerType.THINK_OPEN, index, index + THINK_OPEN_MARKER.length, true)
            }
            if (includeThinkClose && text.startsWith(THINK_CLOSE_MARKER, index)) {
                return MarkerMatch(MarkerType.THINK_CLOSE, index, index + THINK_CLOSE_MARKER.length, true)
            }
            if (includeChannelOpen && text.startsWith(CHANNEL_OPEN_PREFIX, index)) {
                return MarkerMatch(MarkerType.CHANNEL_OPEN, index, index + CHANNEL_OPEN_PREFIX.length, true)
            }
            if (includeChannelClose) {
                findChannelClose(text.substring(index))?.let { close ->
                    return close.copy(
                        start = close.start + index,
                        endExclusive = close.endExclusive + index,
                    )
                }
            }

            val suffix = text.substring(index)
            val partialType = when {
                includeThinkOpen && suffix.length >= 1 && THINK_OPEN_MARKER.startsWith(suffix) -> MarkerType.THINK_OPEN
                includeThinkClose && suffix.length >= 1 && THINK_CLOSE_MARKER.startsWith(suffix) -> MarkerType.THINK_CLOSE
                includeChannelOpen && suffix.length >= 1 && CHANNEL_OPEN_PREFIX.startsWith(suffix) -> MarkerType.CHANNEL_OPEN
                else -> null
            }
            if (partialType != null) {
                return MarkerMatch(partialType, index, text.length, false)
            }
            if (includeChannelClose && (
                (suffix.length >= 1 && CHANNEL_CLOSE_PREFIX.startsWith(suffix)) ||
                    (suffix.startsWith(CHANNEL_CLOSE_PREFIX) &&
                        suffix.drop(CHANNEL_CLOSE_PREFIX.length).all(Char::isWhitespace))
                )
            ) {
                return MarkerMatch(MarkerType.CHANNEL_CLOSE, index, text.length, false)
            }
        }
        return null
    }

    private fun trailingProtocolPrefixStart(text: String): Int? {
        for (start in text.indices.reversed()) {
            val suffix = text.substring(start)
            if (suffix.length >= 1 && PROTOCOL_MARKERS.any { it.startsWith(suffix) }) {
                return start
            }
            if ((suffix.length >= 1 && CHANNEL_CLOSE_PREFIX.startsWith(suffix)) ||
                (suffix.startsWith(CHANNEL_CLOSE_PREFIX) &&
                    suffix.drop(CHANNEL_CLOSE_PREFIX.length).all(Char::isWhitespace))
            ) {
                return start
            }
        }
        return null
    }

    private fun hasTrailingProtocolPrefix(text: String): Boolean =
        trailingProtocolPrefixStart(text) != null

    private fun stripThinkingSyntax(text: String): String =
        stripStructuredSyntax(text)

    private fun stripStructuredSyntax(text: String): String =
        text
            .replace(thinkingHeader, "")
            .replace(THINK_OPEN_MARKER, "")
            .replace(THINK_CLOSE_MARKER, "")
            .replace(closeMarker, "")

    private fun deletePrefix(target: StringBuilder, count: Int) {
        if (count > 0) target.delete(0, minOf(count, target.length))
    }

    private fun deleteLeadingWhitespace(target: StringBuilder) {
        while (target.isNotEmpty() && target[0].isWhitespace()) target.deleteCharAt(0)
    }

    private fun warnAmbiguous(length: Int) {
        onAmbiguousProtocol(length.coerceAtMost(MAX_AMBIGUOUS_LENGTH))
    }

    private companion object {
        const val CHANNEL_OPEN_PREFIX = "<|channel>"
        const val CHANNEL_CLOSE_PREFIX = "<channel|"
        const val THINK_OPEN_MARKER = "<|think|>"
        const val THINK_CLOSE_MARKER = "<|/think|>"
        const val MAX_AMBIGUOUS_LENGTH = 256
        val PROTOCOL_MARKERS = listOf(
            THINKING_CHANNEL_HEADER,
            CHANNEL_OPEN_PREFIX,
            CHANNEL_CLOSE_PREFIX + ">",
            THINK_OPEN_MARKER,
            THINK_CLOSE_MARKER,
        )
    }
}

internal fun containsProtocolSyntaxOrPrefix(text: String): Boolean {
    if (PROTOCOL_MARKERS_FOR_BOUNDARY.any(text::contains)) return true
    for (start in text.indices) {
        val suffix = text.substring(start)
        if (suffix.length >= 2) {
            if (PROTOCOL_MARKERS_FOR_BOUNDARY.any { suffix.startsWith(it) }) return true
            if (PROTOCOL_MARKERS_FOR_BOUNDARY.any { it.startsWith(suffix) }) return true
        }
    }
    return false
}

private val PROTOCOL_MARKERS_FOR_BOUNDARY = listOf(
    "<|channel>",
    "<|channel>thought",
    "<channel|>",
    "<|think|>",
    "<|/think|>",
    "<|/think",
    "<|think",
)

internal fun stripReplayedPrefix(
    current: String,
    emitted: String,
    trimBoundaryWhitespace: Boolean = false,
    allowOverlap: Boolean = false,
    minOverlapLength: Int = 1,
): String {
    if (emitted.isEmpty()) return current
    if (current.startsWith(emitted)) return current.removePrefix(emitted)

    val currentTrimmed = current.trimEnd()
    val emittedTrimmed = emitted.trimEnd()
    if (emittedTrimmed.isNotEmpty() && currentTrimmed.startsWith(emittedTrimmed)) {
        val remainder = currentTrimmed.removePrefix(emittedTrimmed)
        return if (trimBoundaryWhitespace) remainder.trimStart() else remainder
    }
    if (allowOverlap) {
        val exactOverlapRemainder = stripOverlappingReplayPrefix(current, emitted, minOverlapLength)
        if (exactOverlapRemainder != current) {
            return if (trimBoundaryWhitespace) exactOverlapRemainder.trimStart() else exactOverlapRemainder
        }
        val trimmedOverlapRemainder = stripOverlappingReplayPrefix(currentTrimmed, emittedTrimmed, minOverlapLength)
        if (trimmedOverlapRemainder != currentTrimmed) {
            return if (trimBoundaryWhitespace) trimmedOverlapRemainder.trimStart() else trimmedOverlapRemainder
        }
    }
    return current
}

private fun stripOverlappingReplayPrefix(current: String, emitted: String, minOverlapLength: Int): String {
    val maxOverlap = minOf(current.length, emitted.length)
    for (overlapLength in maxOverlap downTo minOverlapLength) {
        if (emitted.endsWith(current.take(overlapLength))) {
            return current.drop(overlapLength)
        }
    }
    return current
}

/**
 * LiteRT-LM implementation of [InferenceEngine].
 *
 * Engine is a thread-safe singleton; Conversation is NOT thread-safe.
 * All operations are dispatched to [LlmDispatcher] (single named thread)
 * to guarantee safety and keep the "llm-inference" thread visible in profiling.
 *
 * Backend selection: AUTO resolves to [HardwareProfileDetector.profile]'s recommended
 * backend (GPU for FLAGSHIP/MID_RANGE, CPU for LOW_POWER — see [HardwareProfileDetector]).
 * SamplerConfig must be null for NPU (hardware sampler is used instead).
 */
@OptIn(ExperimentalApi::class)
@Singleton
class LiteRtInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareProfileDetector: HardwareProfileDetector,
) : InferenceEngine {

    private val _isReady = MutableStateFlow(false)
    override val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    override val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _activeBackend = MutableStateFlow<BackendType?>(null)
    override val activeBackend: StateFlow<BackendType?> = _activeBackend.asStateFlow()

    private val _resolvedMaxTokens = MutableStateFlow(0)
    override val resolvedMaxTokens: StateFlow<Int> = _resolvedMaxTokens.asStateFlow()

    private val _evictionEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val evictionEvents: Flow<Unit> = _evictionEvents

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null
    private var currentConfig: ModelConfig? = null

    /** Ensures only one generation (chat or isolated) runs at a time. */
    private val generationMutex = Mutex()

    /** Tracks conversation resets on GPU to trigger periodic full engine restart (#1293).
     *  Reset to 0 after each full engine restart or after switching away from GPU backend.
     *  Exposed as internal for test observation. */
    internal var gpuResetCount = 0

    /** Prevents concurrent [initialize] calls — a second call while GPU init is in progress
     *  would queue a redundant 47s GPU load immediately after the first finishes. */
    private val isInitializing = AtomicBoolean(false)

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Suspends until the screen is on and the device is interactive.
     *
     * GPU hardware is suspended when the screen is off — calling [createEngineWithFallback]
     * while the screen is off hangs indefinitely. This guard is called at the top of
     * [initialize] to prevent that. If Android does not report an interactive screen within
     * [SCREEN_INTERACTIVE_TIMEOUT_MS], the init proceeds anyway so the single-threaded
     * [LlmDispatcher] does not stay wedged forever after sleep/wake.
     */
    private suspend fun waitForScreenInteractive(): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        if (pm.isInteractive) return true
        Log.i(TAG, "Screen is off — waiting before GPU init (#609)")
        val becameInteractive = waitForInteractiveState(
            isInteractive = { pm.isInteractive },
            pollMs = SCREEN_INTERACTIVE_POLL_MS,
            timeoutMs = SCREEN_INTERACTIVE_TIMEOUT_MS,
        )
        if (becameInteractive) {
            Log.i(TAG, "Screen is on — proceeding with GPU init")
        } else {
            Log.w(
                TAG,
                "Screen did not become interactive within ${SCREEN_INTERACTIVE_TIMEOUT_MS}ms — proceeding with GPU init anyway",
            )
        }
        return becameInteractive
    }

    override suspend fun initialize(config: ModelConfig) {
        if (!isInitializing.compareAndSet(false, true)) {
            Log.d(TAG, "Initialize already in progress — skipping duplicate call")
            return
        }
        try {
            // Start foreground service on the calling thread (typically main) BEFORE entering
            // the background dispatcher. Android 15+ requires startForegroundService() to be
            // called within ~5 seconds of the app being brought to foreground, and only from
            // the main thread.
            InferenceLoadingService.start(context)
            try {
                withContext(LlmDispatcher) {
                    // GPU hardware is suspended when the screen is off; delay init until screen is on.
                    waitForScreenInteractive()
                    _isReady.value = false

                    // Apply hardware-aware defaults when AUTO is specified.
                    val profile = hardwareProfileDetector.profile
                    val resolvedConfig = if (config.backendType == BackendType.AUTO) {
                        config.copy(
                            backendType = profile.recommendedBackend,
                            maxTokens = safeTokenCount(config.maxTokens.coerceAtMost(profile.recommendedMaxTokens)),
                        )
                    } else {
                        config.copy(maxTokens = safeTokenCount(config.maxTokens))
                    }

                    Log.i(TAG, "Initializing engine — model: ${resolvedConfig.modelPath}, " +
                        "backend: ${resolvedConfig.backendType}, tier: ${profile.tier}, " +
                        "maxTokens: ${resolvedConfig.maxTokens} (requested: ${config.maxTokens})")

                    // Sanity-check quantization before spending 10-30s initializing.
                    QuantizationVerifier.verify(
                        modelFile = File(resolvedConfig.modelPath),
                        expectedBytes = estimateExpectedBytes(resolvedConfig.modelPath),
                    )

                    val (eng, backendType) = createEngineWithFallback(resolvedConfig)
                    engine = eng
                    try {
                        conversation = eng.createConversation(buildConversationConfig(backendType, resolvedConfig))
                    } finally {
                        resetExperimentalFlags()
                    }
                    currentConfig = resolvedConfig
                    _activeBackend.value = backendType
                    _resolvedMaxTokens.value = resolvedConfig.maxTokens
                    _isReady.value = true

                    Log.i(TAG, "Engine ready — backend: $backendType, maxTokens: ${resolvedConfig.maxTokens}")
                }
            } finally {
                InferenceLoadingService.stop(context)
            }
        } finally {
            isInitializing.set(false)
        }
    }

    override suspend fun resetConversation() {
        withContext(LlmDispatcher) {
            val eng = engine ?: return@withContext
            val config = currentConfig ?: return@withContext
            val backend = _activeBackend.value ?: BackendType.CPU

            // #1293: On GPU backend (Adreno 740 on S21), LiteRT GPU state accumulates
            // across conversation resets when the engine stays loaded. After
            // GPU_ENGINE_RESTART_INTERVAL resets, do a full engine shutdown + reinitialize
            // to clear GPU state and prevent output quality degradation.
            // Non-GPU backends reset the counter on every call since they don't need this.
            val restartDecision = checkGpuRestartNeeded(gpuResetCount, backend)
            gpuResetCount = restartDecision.updatedCount
            if (restartDecision.shouldRestart) {
                Log.i(TAG, "resetConversation: full engine restart after " +
                    "$GPU_ENGINE_RESTART_INTERVAL GPU resets — clearing accumulated GPU state")
                generationMutex.withLock {
                    _isGenerating.value = false
                }
                // Capture config before shutdown (shutdown sets currentConfig = null).
                // Use captured config for reinit to avoid unsafe !! access on currentConfig.
                shutdown()
                initialize(config)
                Log.i(TAG, "resetConversation: full engine restart complete")
                return@withContext
            }

            // Signal any active generation to stop so it releases generationMutex promptly.
            if (_isGenerating.value) {
                Log.d(TAG, "resetConversation: signalling cancellation to active generation")
                conversation?.cancelProcess()
            }

            // Wait for generationMutex so we don't close the conversation while
            // generate() or generateOnce() is suspended mid-flight using it.
            generationMutex.withLock {
                safeClose(conversation, "conversation")
                try {
                    conversation = eng.createConversation(buildConversationConfig(backend, config))
                } finally {
                    resetExperimentalFlags()
                }
                _isGenerating.value = false
            }
        }
    }

    override suspend fun updateSystemPrompt(systemPrompt: String) {
        withContext(LlmDispatcher) {
            val config = currentConfig ?: return@withContext
            resetConversationForConfig(config.copy(systemPrompt = systemPrompt))
        }
    }

    override suspend fun reconfigureConversation(config: ModelConfig) {
        withContext(LlmDispatcher) {
            val eng = engine ?: throw InferenceException(
                "Engine not initialized — cannot reconfigure conversation. Call initialize() first."
            )
            resetConversationForConfig(config)
            Log.i(TAG, "Conversation reconfigured with new settings")
        }
    }

    override suspend fun shutdown() {
        withContext(LlmDispatcher) {
            _isReady.value = false
            _isGenerating.value = false
            safeCancel(conversation)
            safeClose(conversation, "conversation")
            safeClose(engine, "engine")
            conversation = null
            engine = null
            _activeBackend.value = null
            _resolvedMaxTokens.value = 0
            currentConfig = null
            Log.i(TAG, "Engine shut down")
        }
    }

    /**
     * Fire-and-forget release triggered by Android memory pressure.
     * Marks the engine as not-ready immediately (so callers stop sending work),
     * then tears down the session and weights on [LlmDispatcher].
     * The engine can be reloaded lazily via [initialize] on the next use.
     */
    override fun releaseForMemoryPressure() {
        if (!_isReady.value) return // Already unloaded — nothing to do
        if (_isGenerating.value) return // Don't interrupt active generation
        _isReady.value = false
        _isGenerating.value = false
        _evictionEvents.tryEmit(Unit) // Notify observers before async teardown
        CoroutineScope(LlmDispatcher + SupervisorJob()).launch {
            safeCancel(conversation)
            safeClose(conversation, "conversation")
            safeClose(engine, "engine")
            conversation = null
            engine = null
            _activeBackend.value = null
            currentConfig = null
            Log.i(TAG, "Engine released due to memory pressure")
        }
    }

    // -------------------------------------------------------------------------
    // Generation
    // -------------------------------------------------------------------------

    override fun generate(userMessage: String): Flow<GenerationResult> = callbackFlow {
        generationMutex.lock()
        val conv = conversation
        if (conv == null) {
            generationMutex.unlock()
            close(InferenceException("Engine not initialized — call initialize() first"))
            return@callbackFlow
        }

        _isGenerating.value = true
        InferenceGenerationService.start(context)
        val start = System.currentTimeMillis()
        var firstTokenMs: Long = -1
        var outputTokenCount = 0
        var thinkingCharCount = 0
        val thinkingEnabledForGeneration = currentConfig?.thinkingEnabled == true
        val thinkingStateMachine = ThinkingStreamStateMachine(
            thinkingEnabled = thinkingEnabledForGeneration,
            onAmbiguousProtocol = { length ->
                Log.w(TAG, "thinking_parser: withheld ambiguous protocol fragment len=$length")
            },
        )
        val callbackSequence = AtomicInteger(0)
        fun boundedTraceContent(value: String?): String =
            value.orEmpty()
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .take(256)

        fun traceCallback(message: Message, channelDelta: String?, raw: String) {
            val toolCalls = message.toolCalls.joinToString(";") { toolCall ->
                "${toolCall.name}(${boundedTraceContent(toolCall.arguments?.toString())})"
            }
            Log.d(
                TAG,
                "thinking_trace: generation=1 callback=${callbackSequence.incrementAndGet()} " +
                    "channels=${message.channels.keys.sorted()} " +
                    "thought=\"${boundedTraceContent(channelDelta)}\" " +
                    "raw=\"${boundedTraceContent(raw)}\" " +
                    "toolCalls=\"$toolCalls\"",
            )
        }


        fun emitVisibleToken(delta: String) {
            if (delta.isEmpty()) return
            if (containsProtocolSyntaxOrPrefix(delta)) {
                Log.w(TAG, "thinking_parser: blocked unsafe visible delta len=${delta.length.coerceAtMost(256)}")
                return
            }
            if (firstTokenMs < 0) {
                firstTokenMs = System.currentTimeMillis() - start
                Log.i(TAG, "TTFT (Time to First Token): ${firstTokenMs}ms [backend=${_activeBackend.value}]")
            }
            outputTokenCount++
            trySend(GenerationResult.Token(delta))
        }

        fun emitEmission(emission: ThinkingStreamEmission) {
            emission.thinkingDeltas.forEach { delta ->
                if (delta.isEmpty()) return@forEach
                thinkingCharCount += delta.length
                trySend(GenerationResult.Thinking(delta))
            }
            emission.responseDeltas.forEach(::emitVisibleToken)
        }

        val thinkingContext: Map<String, Any> =
            if (thinkingEnabledForGeneration) mapOf("enable_thinking" to true) else emptyMap()

        try {
            conv.sendMessageAsync(
                Contents.of(Content.Text(userMessage)),
                object : MessageCallback {
                override fun onMessage(message: Message) {
                    val channelDelta = message.channels["thought"]
                    val raw = message.toString()
                    traceCallback(message, channelDelta, raw)
                    emitEmission(
                        thinkingStateMachine.consume(
                            channelDelta = channelDelta,
                            rawMessage = raw,
                        ),
                    )
                }

                override fun onDone() {
                    emitEmission(thinkingStateMachine.finish())
                    val durationMs = System.currentTimeMillis() - start
                    val generationMs = durationMs - firstTokenMs.coerceAtLeast(0)
                    val tokensPerSec = if (generationMs > 0 && outputTokenCount > 0) {
                        outputTokenCount * 1000.0 / generationMs
                    } else 0.0
                    if (thinkingCharCount > 0) {
                        Log.d("KernelAI", "Thinking tokens: $thinkingCharCount chars")
                    }
                    Log.i(TAG, "Generation complete: total=${durationMs}ms, TTFT=${firstTokenMs}ms, " +
                        "tokens=$outputTokenCount, speed=${"%.1f".format(tokensPerSec)}tok/s [backend=${_activeBackend.value}]")
                    Log.d(
                        TAG,
                        "thinking_trace: generation=1 complete callbacks=${callbackSequence.get()} " +
                            "thinkingChars=$thinkingCharCount visibleTokens=$outputTokenCount",
                    )
                    _isGenerating.value = false
                    InferenceGenerationService.stop(context)
                    trySend(GenerationResult.Complete(durationMs = durationMs))
                    close()
                }

                override fun onError(throwable: Throwable) {
                    _isGenerating.value = false
                    InferenceGenerationService.stop(context)
                    if (throwable is CancellationException) {
                        Log.i(TAG, "Generation cancelled by user")
                        close()
                    } else {
                        Log.e(TAG, "Generation error", throwable)
                        close(InferenceException("Generation failed: ${throwable.message}", throwable))
                    }
                }
            },
            thinkingContext,
        )
        } catch (e: Exception) {
            _isGenerating.value = false
            InferenceGenerationService.stop(context)
            generationMutex.unlock()
            close(InferenceException("sendMessageAsync failed: ${e.message}", e))
            return@callbackFlow
        }

        // When the Flow collector cancels (e.g. user navigates away), stop inference.
        awaitClose {
            _isGenerating.value = false
            InferenceGenerationService.stop(context)
            try { conv.cancelProcess() } catch (e: Exception) {
                Log.w(TAG, "cancelProcess failed in awaitClose — ignoring", e)
            }
            generationMutex.unlock()
        }
    }.flowOn(LlmDispatcher)

    override fun cancelGeneration() {
        conversation?.cancelProcess()
        _isGenerating.value = false
        InferenceGenerationService.stop(context)
    }

    /**
     * Generate a response using an **isolated conversation** that does not share KV
     * cache state with the active chat. Acquires [generationMutex] — if the engine
     * is currently generating, this suspends until the active generation completes.
     */
    override suspend fun generateOnce(
        prompt: String,
        systemPrompt: String?,
        thinkingEnabled: Boolean?,
        stopOnFirstJsonObject: Boolean,
    ): String {
        // Start foreground service on the calling thread (typically main) BEFORE entering
        // the background dispatcher. Android 15+ requires startForegroundService() to be
        // called within ~5 seconds of the app being brought to foreground, and only from
        // the main thread.
        InferenceGenerationService.start(context)
        return try {
            withContext(LlmDispatcher) {
                val config = currentConfig ?: return@withContext ""
                val requestedSystemPrompt = systemPrompt?.takeIf { it.isNotBlank() }
                val requestedThinkingEnabled = thinkingEnabled ?: config.thinkingEnabled
                val requestedConfig = config.copy(
                    systemPrompt = requestedSystemPrompt ?: config.systemPrompt,
                    thinkingEnabled = requestedThinkingEnabled,
                )
                val shouldSwapConfig = requestedConfig != config
                val timeoutMs = if (requestedSystemPrompt != null) 60_000L else 30_000L
                try {
                    if (shouldSwapConfig) {
                        resetConversationForConfig(requestedConfig)
                    }
                    val conv = conversation ?: return@withContext ""
                    var acquired = false
                    for (attempt in 0 until 20) {
                        if (generationMutex.tryLock()) {
                            acquired = true
                            break
                        }
                        Log.d(TAG, "generateOnce: mutex busy, retry ${attempt + 1}/20")
                        delay(250L)
                    }
                    if (!acquired) {
                        Log.w(TAG, "generateOnce: failed to acquire mutex after 5s — engine busy")
                        return@withContext ""
                    }
                    _isGenerating.value = true
                    try {
                        val response = StringBuilder()
                        val jsonAccumulator = if (stopOnFirstJsonObject) JsonObjectAccumulator() else null
                        val latch = CompletableDeferred<String>()
                        val finished = AtomicBoolean(false)
                        conv.sendMessageAsync(
                            Contents.of(Content.Text(prompt)),
                            object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    if (finished.get()) return
                                    val text = message.toString()
                                    if (text.isEmpty() || text.startsWith("<ctrl")) return
                                    response.append(text)
                                    val completedJson = jsonAccumulator?.append(text)
                                    if (completedJson != null && finished.compareAndSet(false, true)) {
                                        latch.complete(completedJson)
                                        try {
                                            conv.cancelProcess()
                                        } catch (cancelError: Exception) {
                                            Log.d(TAG, "generateOnce: cancelProcess failed after JSON completion — ignoring", cancelError)
                                        }
                                    }
                                }
                                override fun onDone() {
                                    if (finished.compareAndSet(false, true)) {
                                        latch.complete(response.toString())
                                    }
                                }
                                override fun onError(throwable: Throwable) {
                                    if (finished.compareAndSet(false, true)) {
                                        latch.completeExceptionally(throwable)
                                    }
                                }
                            },
                            if (requestedThinkingEnabled) mapOf("enable_thinking" to true) else emptyMap(),
                        )
                        try {
                            withTimeout(timeoutMs) { latch.await() }
                        } catch (e: TimeoutCancellationException) {
                            try {
                                conv.cancelProcess()
                            } catch (cancelError: Exception) {
                                Log.w(TAG, "generateOnce: cancelProcess failed after timeout — ignoring", cancelError)
                            }
                            Log.w(TAG, "generateOnce: timed out after ${timeoutMs / 1000}s waiting for generation — returning empty")
                            ""
                        }
                    } finally {
                        _isGenerating.value = false
                        generationMutex.unlock()
                    }
                } finally {
                    if (shouldSwapConfig) {
                        resetConversationForConfig(config)
                    }
                }
            }
        } finally {
            InferenceGenerationService.stop(context)
        }
    }
    override suspend fun generateStructuredOnce(
        prompt: String,
        spec: StructuredOutputSpec,
        systemPrompt: String?,
        thinkingEnabled: Boolean?,
    ): String {
        // Start foreground service on the calling thread (typically main) BEFORE entering
        // the background dispatcher. Android 15+ requires startForegroundService() to be
        // called within ~5 seconds of the app being brought to foreground, and only from
        // the main thread.
        InferenceGenerationService.start(context)
        return try {
            withContext(LlmDispatcher) {
                val config = currentConfig ?: run {
                    Log.w(TAG, "generateStructuredOnce: currentConfig is null — engine not initialized?")
                    return@withContext ""
                }
                Log.d(
                    TAG,
                    "generateStructuredOnce: spec='${spec.toolName}', schemaLen=${spec.jsonSchema.length}, thinking=$thinkingEnabled",
                )
                val requestedSystemPrompt = systemPrompt?.takeIf { it.isNotBlank() }
                val requestedThinkingEnabled = thinkingEnabled ?: config.thinkingEnabled
                // Build a synthetic OpenAPI tool from the spec.
                // The synthetic tool echoes its input back, so the model sees:
                // 1. Its own tool call with arguments
                // 2. The same arguments echoed back as tool response
                // This is effectively a no-op for the model, but enables constrained decoding
                // via the tool-calling path.
                val syntheticToolProvider = tool(
                    object : OpenApiTool {
                        private val desc = JSONObject().apply {
                            put("name", spec.toolName)
                            if (spec.toolDescription.isNotBlank()) put("description", spec.toolDescription)
                            put("parameters", JSONObject(spec.jsonSchema))
                        }.toString()
                        override fun getToolDescriptionJsonString(): String = desc
                        override fun execute(paramsJsonString: String): String = paramsJsonString
                    },
                )
                val requestedConfig = config.copy(
                    systemPrompt = requestedSystemPrompt ?: config.systemPrompt,
                    thinkingEnabled = requestedThinkingEnabled,
                )
                val shouldSwapConfig = requestedConfig != config
                val timeoutMs = 60_000L
                try {
                    // Acquire mutex FIRST — prevents closing shared conversation while another
                    // thread (e.g. generateOnce) is using it.
                    var acquired = false
                    for (attempt in 0 until 20) {
                        if (generationMutex.tryLock()) {
                            acquired = true
                            break
                        }
                        Log.d(TAG, "generateStructuredOnce: mutex busy, retry ${attempt + 1}/20")
                        delay(250L)
                    }
                    if (!acquired) {
                        Log.w(TAG, "generateStructuredOnce: failed to acquire mutex after 5s — engine busy")
                        return@withContext ""
                    }
                    try {
                        // Now safe to swap config and close shared conversation
                        if (shouldSwapConfig) {
                            currentConfig = requestedConfig
                            safeClose(conversation, "conversation")
                        }
                        val eng = engine ?: run {
                            Log.w(TAG, "generateStructuredOnce: engine is null — was it evicted?")
                            return@withContext ""
                        }
                        val convConfig = buildConversationConfig(_activeBackend.value ?: BackendType.CPU, requestedConfig)
                        // Isolate to synthetic tool only — no other tools interfere with constrained decoding.
                        val convConfigWithTool = convConfig.copy(
                            tools = listOf(syntheticToolProvider),
                            automaticToolCalling = false,
                        )
                        // With automaticToolCalling=false, the model's tool call is returned directly
                        // to the callback instead of being auto-executed.
                        // Enable constrained decoding for synthetic tool calls.
                        // buildConversationConfig only sets this when config.toolProvider is non-null,
                        // but generateStructuredOnce adds tools via convConfig.copy() — so we set it
                        // explicitly here. Must be set before createConversation() (Gallery pattern).
                        ExperimentalFlags.enableConversationConstrainedDecoding = true
                        try {
                            val conv = try {
                                eng.createConversation(convConfigWithTool)
                            } catch (e: Exception) {
                                Log.w(TAG, "generateStructuredOnce: conversation creation failed", e)
                                resetExperimentalFlags()
                                return@withContext ""
                            }
                            _isGenerating.value = true
                            try {
                                val latch = CompletableDeferred<String>()
                                val finished = AtomicBoolean(false)
                                val capturedToolJson = AtomicReference<String?>(null)
                                val jsonAccumulator = JsonObjectAccumulator()
                                val TEXT_FALLBACK_MAX_CHARS = 2000
                                val responseBuilder = StringBuilder()
                                conv.sendMessageAsync(
                                    Contents.of(Content.Text(prompt)),
                                    object : MessageCallback {
                                        override fun onMessage(message: Message) {
                                            if (finished.get()) return
                                            // Priority 1: tool call matching spec
                                            val toolCalls = message.toolCalls
                                            if (toolCalls.isNotEmpty()) {
                                                Log.d(
                                                    TAG,
                                                    "generateStructuredOnce: received ${toolCalls.size} tool call(s) — first='${toolCalls.firstOrNull()?.name}'",
                                                )
                                                if (toolCalls.size == 1) {
                                                    val call = toolCalls.single()
                                                    if (call.name == spec.toolName) {
                                                        Log.d(
                                                            TAG,
                                                            "generateStructuredOnce: matched tool call '${call.name}', arguments length=${call.arguments?.toString()?.length ?: 0}",
                                                        )
                                                        // Serialize Map → proper JSON (not Kotlin Map.toString())
                                                        capturedToolJson.set(JSONObject(call.arguments).toString())
                                                        if (finished.compareAndSet(false, true)) {
                                                            latch.complete(capturedToolJson.get()!!)
                                                        }
                                                        return
                                                    } else {
                                                        Log.d(
                                                            TAG,
                                                            "generateStructuredOnce: tool call name mismatch — expected='${spec.toolName}', got='${call.name}'",
                                                        )
                                                    }
                                                } else {
                                                    Log.w(
                                                        TAG,
                                                        "generateStructuredOnce: multiple tool calls (${toolCalls.size}), expected exactly one — failing",
                                                    )
                                                }
                                            }
                                            // Priority 2: accumulate text for JSON extraction fallback
                                            val text = message.toString()
                                            if (text.isNotEmpty() && !text.startsWith("<ctrl")) {
                                                responseBuilder.append(text)
                                                Log.d(
                                                    TAG,
                                                    "generateStructuredOnce: text chunk appended, total=${responseBuilder.length}",
                                                )
                                                // Fail fast: if model generates too much text without calling the tool
                                                // or producing JSON, cancel to avoid 60s timeout.
                                                if (responseBuilder.length > TEXT_FALLBACK_MAX_CHARS && capturedToolJson.get() == null) {
                                                    Log.w(
                                                        TAG,
                                                        "generateStructuredOnce: model generated ${responseBuilder.length} chars of text without tool call or JSON — cancelling",
                                                    )
                                                    try { conv.cancelProcess() } catch (ce: Exception) {}
                                                    if (finished.compareAndSet(false, true)) {
                                                        latch.complete("")
                                                    }
                                                    return
                                                }
                                                jsonAccumulator.append(text)?.let { json ->
                                                    Log.d(
                                                        TAG,
                                                        "generateStructuredOnce: JSON object extracted from text, length=${json.length}",
                                                    )
                                                    capturedToolJson.set(json)
                                                    if (finished.compareAndSet(false, true)) {
                                                        latch.complete(json)
                                                    }
                                                }
                                            }
                                        }
                                        override fun onDone() {
                                            if (finished.compareAndSet(false, true)) {
                                                if (capturedToolJson.get() != null) return
                                                Log.w(
                                                    TAG,
                                                    "generateStructuredOnce: model returned no tool call or JSON for '${spec.toolName}'",
                                                )
                                                if (responseBuilder.isNotEmpty()) {
                                                    Log.d(
                                                        TAG,
                                                        "generateStructuredOnce: onDone with ${responseBuilder.length} chars of text but no JSON",
                                                    )
                                                }
                                                latch.complete("")
                                            }
                                        }
                                        override fun onError(throwable: Throwable) {
                                            if (finished.compareAndSet(false, true)) {
                                                Log.e(
                                                    TAG,
                                                    "generateStructuredOnce: error during structured generation",
                                                    throwable,
                                                )
                                                latch.completeExceptionally(throwable)
                                            }
                                        }
                                    },
                                    if (requestedThinkingEnabled) mapOf("enable_thinking" to true) else emptyMap(),
                                )
                                var result: String? = null
                                try {
                                    result = withTimeout(timeoutMs) { latch.await() }
                                } catch (e: TimeoutCancellationException) {
                                    try { conv.cancelProcess() } catch (ce: Exception) {}
                                    Log.w(TAG, "generateStructuredOnce: timed out after ${timeoutMs / 1000}s")
                                    result = ""
                                }
                                Log.d(
                                    TAG,
                                    "generateStructuredOnce: completed — result length=${result?.length ?: 0}, wasToolCall=${result?.let { it != "" } ?: false}",
                                )
                                result
                            } finally {
                                _isGenerating.value = false
                                safeClose(conv, "structured-conv")
                            }
                        } finally {
                            resetExperimentalFlags()
                        }
                    } finally {
                        generationMutex.unlock()
                        if (shouldSwapConfig) {
                            resetConversationForConfig(config)
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "generateStructuredOnce: error", e)
                    ""
                }
            }
        } finally {
            InferenceGenerationService.stop(context)
        }
    }

    private suspend fun resetConversationForConfig(config: ModelConfig) {
        val eng = engine ?: return
        val backend = _activeBackend.value ?: BackendType.CPU
        currentConfig = config

        if (_isGenerating.value) {
            Log.d(TAG, "resetConversationForConfig: signalling cancellation to active generation")
            conversation?.cancelProcess()
        }

        generationMutex.withLock {
            safeClose(conversation, "conversation")
            try {
                conversation = eng.createConversation(buildConversationConfig(backend, config))
            } finally {
                resetExperimentalFlags()
            }
            _isGenerating.value = false
            Log.i(TAG, "System prompt updated and conversation reset")
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    private suspend fun createEngineWithFallback(config: ModelConfig): Pair<Engine, BackendType> {
        val orderedBackends: List<BackendType> = when (config.backendType) {
            BackendType.CPU -> listOf(BackendType.CPU)
            BackendType.GPU -> listOf(BackendType.GPU, BackendType.CPU)
            BackendType.NPU -> listOf(BackendType.NPU, BackendType.GPU, BackendType.CPU)
            BackendType.AUTO -> listOf(BackendType.GPU, BackendType.CPU)
        }

        // Check available memory before attempting GPU.
        // 2 GB absolute floor catches genuinely low-memory devices (4-6 GB).
        // On 8 GB devices ~2.5 GB is typically free; GPU init has its own 60s
        // timeout guard so we don't need an overly conservative threshold (#684).
        val availMem = getAvailableMemoryBytes()
        val modelFile = File(config.modelPath)
        val modelSize = if (modelFile.exists()) modelFile.length() else 0L
        val skipGpuForMemory = availMem < MIN_AVAIL_MEM_FOR_GPU_BYTES
        if (skipGpuForMemory) {
            Log.w(TAG, "Available memory (${availMem / (1024*1024)} MB) below GPU minimum " +
                "(${MIN_AVAIL_MEM_FOR_GPU_BYTES / (1024*1024)} MB) — skipping GPU backend")
        } else if (modelSize > 0 && availMem < modelSize) {
            Log.w(TAG, "Available memory (${availMem / (1024*1024)} MB) is less than model file " +
                "size (${modelSize / (1024*1024)} MB) — GPU init may trigger OOM kill")
        }

        var lastException: Exception? = null
        for (backendType in orderedBackends) {
            // Honour the memory check: if GPU was skipped, don't try GPU or NPU.
            if (skipGpuForMemory && backendType != BackendType.CPU) {
                Log.d(TAG, "Skipping backend $backendType due to memory pressure")
                continue
            }
            try {
                Log.d(TAG, "Trying backend: $backendType")
                val engineConfig = EngineConfig(
                    modelPath = config.modelPath,
                    backend = backendType.toBackend(context),
                    maxNumTokens = config.maxTokens,
                    cacheDir = context.cacheDir.absolutePath,
                )
                // MTP speculative decoding must be enabled BEFORE Engine.initialize() —
                // Gallery pattern: the flag is compiled into the engine at init time, not at
                // createConversation() time. Check Capabilities first to guard unsupported models.
                val speculativeDecoding = resolveSpeculativeDecodingForInit(
                    requested = config.speculativeDecodingEnabled,
                    modelPath = config.modelPath,
                    onProbeFailure = { modelPath, error ->
                        Log.w(TAG, "Speculative decoding capability probe failed for $modelPath: ${error.message}")
                    },
                )
                Log.d(TAG, "Speculative decoding: requested=${config.speculativeDecodingEnabled} active=$speculativeDecoding")
                // Construct Engine in a try-finally so we close it on failure — the timeout
                // is advisory only (native JNI blocking calls may not be interrupted).
                var engine: Engine? = null
                try {
                    engine = withTimeout(GPU_INIT_TIMEOUT_MS) {
                        withSpeculativeDecodingEnabledForInit(speculativeDecoding) {
                            val e = Engine(engineConfig)
                            e.initialize()
                            e
                        }
                    }
                    Log.i(TAG, "Backend $backendType initialized successfully")
                    return Pair(engine, backendType)
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Backend $backendType timed out after ${GPU_INIT_TIMEOUT_MS}ms — falling back")
                    engine?.close()
                    lastException = e
                } catch (e: Exception) {
                    Log.w(TAG, "Backend $backendType failed: ${e.message}")
                    engine?.close()
                    lastException = e
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend $backendType failed: ${e.message}")
                lastException = e
            }
        }

        val profile = hardwareProfileDetector.profile
        val memInfo = "${availMem / (1024 * 1024)} MB available / ${profile.totalRamBytes / (1024 * 1024 * 1024)} GB total"
        val summary = when {
            skipGpuForMemory -> "GPU skipped (low memory — $memInfo)"
            lastException is TimeoutCancellationException -> "GPU init timed out (${GPU_INIT_TIMEOUT_MS / 1000}s) — $memInfo"
            lastException != null -> "GPU init failed: ${lastException.message} — $memInfo"
            else -> "unknown error — $memInfo"
        }
        throw InferenceException(
            "Model loading failed ($summary). " +
                "Tier: ${profile.tier.name}, SoC: ${profile.socManufacturer} ${profile.socModel}. " +
                "Last backend error: ${lastException?.message ?: "none"}",
            lastException,
        )
    }

    /**
     * Returns available system memory in bytes, or [Long.MAX_VALUE] if [ActivityManager]
     * is unavailable so the memory check is effectively skipped in that case.
     */
    private fun getAvailableMemoryBytes(): Long {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return Long.MAX_VALUE
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            memInfo.availMem
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query available memory: ${e.message}")
            Long.MAX_VALUE
        }
    }

    @OptIn(ExperimentalApi::class)
    private fun buildConversationConfig(
        backendType: BackendType,
        config: ModelConfig,
    ): ConversationConfig {
        // NPU uses hardware sampler — setting SamplerConfig causes a crash
        val samplerConfig = if (backendType == BackendType.NPU) null else SamplerConfig(
            topK = config.topK,
            topP = config.topP.toDouble(),
            temperature = config.temperature.toDouble(),
        )
        var systemInstruction = config.systemPrompt
            ?.takeIf { it.isNotBlank() }
            ?.let { Contents.of(Content.Text(it)) }

        val tools = config.toolProvider?.let { listOf(it) } ?: emptyList()

        // Two things are required to enable thinking:
        // 1. Register the "thought" channel in ConversationConfig — this routes tokens between
        //    <|think|> and <|/think|> to message.channels["thought"] instead of message.toString().
        // 2. Pass extraContext = mapOf("enable_thinking" to true) in sendMessageAsync — this sets
        //    the Jinja template variable that injects <|think|> before the model's response,
        //    triggering chain-of-thought generation. Without this, no thinking tokens are emitted.
        val channels = if (config.thinkingEnabled) {
            // Append thinking-channel instructions to the system prompt so the model
            // knows to close the thought channel before outputting the final answer.
            val thinkingInstruction = "\n\nIMPORTANT: When responding, place your reasoning between <|think|> and <|/think|> tags. Your final answer must come AFTER the <|/think|> tag.\n"
            val enhancedSystemPrompt = (config.systemPrompt ?: "") + thinkingInstruction
            systemInstruction = enhancedSystemPrompt.takeIf { it.isNotBlank() }?.let { Contents.of(Content.Text(it)) }
            listOf(Channel("thought", "<|think|>", "<|/think|>"))
        } else {
            emptyList()
        }

        // Enable constrained decoding for well-formed tool calls (Google Gallery pattern).
        // Must be set before createConversation() and reset after via resetExperimentalFlags().
        if (tools.isNotEmpty()) {
            ExperimentalFlags.enableConversationConstrainedDecoding = true
        }

        return ConversationConfig(
            samplerConfig = samplerConfig,
            systemInstruction = systemInstruction,
            tools = tools,
            channels = channels,
        )
    }

    /** Reset experimental flags after each createConversation() call (Gallery pattern). */
    private fun resetExperimentalFlags() {
        ExperimentalFlags.enableConversationConstrainedDecoding = false
        // Note: enableSpeculativeDecoding is reset immediately after engine.initialize() in
        // createEngineWithFallback() — it does not need to be reset here.
    }

    private fun safeCancel(conv: com.google.ai.edge.litertlm.Conversation?) {
        try { conv?.cancelProcess() } catch (e: Exception) { Log.w(TAG, "cancelProcess: ${e.message}") }
    }

    private fun safeClose(closeable: AutoCloseable?, label: String) {
        try { closeable?.close() } catch (e: Exception) { Log.w(TAG, "close $label: ${e.message}") }
    }

    /**
     * Returns a rough expected byte count for the given model path.
     * Falls back to 0 (skips quantization check) if the model isn't in [KernelModel].
     */
    private fun estimateExpectedBytes(modelPath: String): Long {
        val fileName = File(modelPath).name
        return com.kernel.ai.core.inference.download.KernelModel.entries
            .firstOrNull { it.fileName == fileName }
            ?.approxSizeBytes ?: 0L
    }

    companion object {
        /**
         * Avoids exact powers-of-2 token counts that trigger a buffer-alignment bug
         * in LiteRT's GPU `reshape::Eval` operation (observed on Adreno 740 / SM8550).
         * Nudges e.g. 4096→4000, 8192→8000 while leaving non-power-of-2 values untouched.
         */
        internal fun safeTokenCount(tokens: Int): Int {
            if (tokens <= 0) return tokens
            // Check if tokens is an exact power of 2
            if (tokens and (tokens - 1) == 0) {
                val safe = (tokens * 125) / 128  // ~97.6% — e.g. 4096→4000, 8192→8000
                Log.w("LiteRtInferenceEngine",
                    "Adjusted maxTokens from $tokens to $safe (avoid GPU reshape alignment bug)")
                return safe
            }
            return tokens
        }
    }
}
