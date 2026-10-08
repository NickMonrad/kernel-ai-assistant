package com.kernel.ai.debug.transcript

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.kernel.ai.core.memory.entity.MessageEntity
import com.kernel.ai.core.memory.repository.ConversationRepository
import com.kernel.ai.core.skills.LocalToolDiagnosticCapture
import com.kernel.ai.core.skills.LocalToolCallDiagnostic
import com.kernel.ai.core.skills.LocalGenerationAttemptDiagnostic
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug-only bridge for exporting one local llm_tools conversation and its full tool trace. */
class LocalLlmToolsTranscriptProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val callerUid = Binder.getCallingUid()
        if (callerUid != Process.SHELL_UID && callerUid != Process.myUid()) {
            return response(ok = false, error = "caller_not_allowed")
        }
        return try {
            when (method) {
                METHOD_BEGIN -> {
                    transcriptFile().delete()
                    dependencies().localToolDiagnosticCapture().begin()
                    response(ok = true)
                }
                METHOD_EXPORT -> {
                    exportTranscript()
                    response(ok = true)
                }
                METHOD_CLEAR -> {
                    dependencies().localToolDiagnosticCapture().finish()
                    transcriptFile().delete()
                    response(ok = true)
                }
                else -> response(ok = false, error = "unknown_method")
            }
        } catch (_: Exception) {
            response(ok = false, error = "transcript_operation_failed")
        }
    }

    private fun exportTranscript() {
        val toolSnapshot = dependencies().localToolDiagnosticCapture().finish()
        val repository = dependencies().conversationRepository()
        val conversationAndMessages = runBlocking(Dispatchers.IO) {
            val conversation = repository.observeConversations().first().firstOrNull()
            val messages = conversation?.let { repository.getMessagesOnce(it.id) }.orEmpty()
            conversation?.id to messages
        }
        val conversationId = conversationAndMessages.first
        val messages = conversationAndMessages.second
        val userPrompt = messages.lastOrNull { it.role == "user" }?.content
        val finalResponse = messages.lastOrNull { it.role == "assistant" }?.content
        val json = JSONObject().apply {
            put("schema_version", 1)
            putNullable("conversation_id", conversationId)
            putNullable("user_prompt", userPrompt)
            putNullable("final_visible_response", finalResponse)
            put("messages", JSONArray().apply { messages.forEach { put(it.toDiagnosticJson()) } })
            put("tool_calls", JSONArray().apply { toolSnapshot.calls.forEach { put(it.toDiagnosticJson()) } })
            put("terminal_call", toolSnapshot.terminalCall?.toDiagnosticJson() ?: JSONObject.NULL)
            put(
                "generation_attempts",
                JSONArray().apply {
                    toolSnapshot.generationAttempts.forEach { put(it.toDiagnosticJson()) }
                },
            )
        }
        transcriptFile().writeText(json.toString(2), Charsets.UTF_8)
    }

    private fun MessageEntity.toDiagnosticJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("conversation_id", conversationId)
        put("role", role)
        put("content", content)
        putNullable("thinking_text", thinkingText)
        put("timestamp", timestamp)
        putNullable("tool_call_json", toolCallJson)
    }

    private fun LocalToolCallDiagnostic.toDiagnosticJson(): JSONObject = JSONObject().apply {
        put("order", order)
        put("name", name)
        put("arguments", JSONObject(arguments))
        put("terminal", terminal)
        putNullable("result_type", resultType)
        putNullable("result_content", resultContent)
        put("tool_result", toolResult?.let { JSONObject(it) } ?: JSONObject.NULL)
        putNullable("succeeded", succeeded)
        putNullable("direct_reply", directReply)
        putNullable("returned_to_gemma", returnedToGemma)
    }

    private fun LocalGenerationAttemptDiagnostic.toDiagnosticJson(): JSONObject = JSONObject().apply {
        put("order", order)
        put("full_content", fullContent)
        put("raw_thinking", rawThinking)
    }

    private fun dependencies(): LocalLlmToolsTranscriptEntryPoint = EntryPointAccessors.fromApplication(
        requireNotNull(context).applicationContext,
        LocalLlmToolsTranscriptEntryPoint::class.java,
    )

    private fun transcriptFile(): File = File(requireNotNull(context).cacheDir, TRANSCRIPT_FILE_NAME)

    private fun response(ok: Boolean, error: String? = null): Bundle = Bundle().apply {
        putBoolean(RESULT_OK, ok)
        if (error != null) putString(RESULT_ERROR, error)
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = throw UnsupportedOperationException("Use ContentProvider.call")

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Use ContentProvider.call")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Use ContentProvider.call")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = throw UnsupportedOperationException("Use ContentProvider.call")

    private companion object {
        const val METHOD_BEGIN = "begin_local_transcript"
        const val METHOD_EXPORT = "export_local_transcript"
        const val METHOD_CLEAR = "clear_local_transcript"
        const val RESULT_OK = "ok"
        const val RESULT_ERROR = "error"
        const val TRANSCRIPT_FILE_NAME = "llm_tools_local_transcript.json"
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocalLlmToolsTranscriptEntryPoint {
    fun conversationRepository(): ConversationRepository
    fun localToolDiagnosticCapture(): LocalToolDiagnosticCapture
}
