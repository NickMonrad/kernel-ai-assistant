package com.kernel.ai.feature.settings

import com.kernel.ai.feature.settings.DiagnosticLogRedactor.REDACTED_MARKER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.api.Test

class DiagnosticLogRedactorTest {

    private val r = REDACTED_MARKER

    @ParameterizedTest(name = "{0}")
    @MethodSource("redactedMessages")
    fun `redacts user content from app log messages`(raw: String, expected: String) {
        assertEquals(expected, DiagnosticLogRedactor.redactMessage(raw))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralMessages")
    fun `keeps structural log messages unchanged`(message: String) {
        assertEquals(message, DiagnosticLogRedactor.redactMessage(message))
    }

    @Test
    fun `redacts logcat entries but keeps headers, tags and timings`() {
        val logcat = """
            --------- beginning of main
            11-14 09:12:01.100  4321  4400 I NativeVoiceInput: Android native STT result: sessionId=7 mode=COMMAND transcript='remind me to call mum' language=en-NZ
            11-14 09:12:01.120  4321  4400 W TranscriptNormaliser: MISHEARD_ADD applied to "at milk to shopping" → "add milk to shopping"
            11-14 09:12:01.300  4321  4321 D KernelAI: ADB_INTENT_TRACE commandId=c42 input=add milk to shopping submitMode=VOICE
            11-14 09:12:02.900  4321  4500 I LiteRtInferenceEngine: TTFT (Time to First Token): 2310ms [backend=GPU]
        """.trimIndent()

        val redacted = DiagnosticLogRedactor.redactLogcat(logcat)

        val expected = """
            --------- beginning of main
            11-14 09:12:01.100  4321  4400 I NativeVoiceInput: Android native STT result: sessionId=7 mode=COMMAND transcript='$r' language=en-NZ
            11-14 09:12:01.120  4321  4400 W TranscriptNormaliser: MISHEARD_ADD applied to "$r" → "$r"
            11-14 09:12:01.300  4321  4321 D KernelAI: ADB_INTENT_TRACE commandId=c42 input=$r submitMode=VOICE
            11-14 09:12:02.900  4321  4500 I LiteRtInferenceEngine: TTFT (Time to First Token): 2310ms [backend=GPU]
        """.trimIndent()
        assertEquals(expected, redacted)
    }

    @Test
    fun `redacts multi-line profile dump but keeps the stack trace of a separate entry`() {
        val logcat = """
            11-14 09:13:00.000  4321  4600 D UserProfileExtraction: Profile LLM extraction succeeded:
            11-14 09:13:00.000  4321  4600 D UserProfileExtraction: name: Aroha
            11-14 09:13:00.000  4321  4600 D UserProfileExtraction: city: Wellington
            11-14 09:13:01.000  4321  4600 E KernelAI: Inference exception in sendMessage — generation failed
            11-14 09:13:01.000  4321  4600 E KernelAI: java.lang.IllegalStateException: engine closed
            11-14 09:13:01.000  4321  4600 E KernelAI: 	at com.kernel.ai.core.inference.LiteRtInferenceEngine.generate(LiteRtInferenceEngine.kt:1512)
        """.trimIndent()

        val redacted = DiagnosticLogRedactor.redactLogcat(logcat)

        assertFalse(redacted.contains("Aroha"))
        assertFalse(redacted.contains("Wellington"))
        assertTrue(redacted.contains("UserProfileExtraction: Profile LLM extraction succeeded:\n"))
        assertTrue(redacted.contains("UserProfileExtraction: $r\n"))
        assertTrue(redacted.contains("java.lang.IllegalStateException: engine closed"))
        assertTrue(redacted.contains("LiteRtInferenceEngine.generate(LiteRtInferenceEngine.kt:1512)"))
    }

    @Test
    fun `redacts quoted chat text that spans several logcat lines`() {
        val logcat = """
            11-14 09:14:00.000  4321  4321 D KernelAI: Raw title output: "Shopping list
            11-14 09:14:00.000  4321  4321 D KernelAI: for Saturday"
        """.trimIndent()

        val redacted = DiagnosticLogRedactor.redactLogcat(logcat)

        assertFalse(redacted.contains("Shopping"))
        assertFalse(redacted.contains("Saturday"))
        assertEquals("11-14 09:14:00.000  4321  4321 D KernelAI: Raw title output: \"$r\"", redacted)
    }

    @Test
    fun `redactText scrubs an uncaught exception message but keeps the stack`() {
        val record = """
            Exception: java.lang.NumberFormatException
            Message: For input string: "call Mum at 5"
            Stack trace:
            	at java.lang.Integer.parseInt(Integer.java:747)
        """.trimIndent()

        val redacted = DiagnosticLogRedactor.redactText(record)

        assertFalse(redacted.contains("Mum"))
        assertTrue(redacted.contains("Message: For input string: \"$r\""))
        assertTrue(redacted.contains("at java.lang.Integer.parseInt(Integer.java:747)"))
    }

    companion object {
        private const val R = REDACTED_MARKER

        @JvmStatic
        fun redactedMessages(): List<Arguments> = listOf(
            // STT transcripts and partials
            Arguments.of(
                "Android native STT partial: sessionId=12 mode=COMMAND transcript='what's the weather in Taupō'",
                "Android native STT partial: sessionId=12 mode=COMMAND transcript='$R'",
            ),
            Arguments.of(
                "KIWI_PHONETIC applied to \"set a time a for ten\" → \"set a timer for ten\"",
                "KIWI_PHONETIC applied to \"$R\" → \"$R\"",
            ),
            Arguments.of(
                "VoiceCommandService: handling transcript=\"text Sam I'm late\" mode=WIDGET",
                "VoiceCommandService: handling transcript=\"$R\" mode=WIDGET",
            ),
            Arguments.of(
                "ActionsViewModel: normalized voice command \"ad eggs\" -> \"add eggs\"",
                "ActionsViewModel: normalized voice command \"$R\" -> \"$R\"",
            ),
            Arguments.of(
                "Stop command intercepted in back-and-forth loop: \"stop\"",
                "Stop command intercepted in back-and-forth loop: \"$R\"",
            ),
            // Chat input and assistant replies
            Arguments.of(
                "ADB_INTENT_TRACE commandId=abc123 input=tell me about my sister's birthday submitMode=TEXT",
                "ADB_INTENT_TRACE commandId=abc123 input=$R submitMode=TEXT",
            ),
            Arguments.of(
                "ADB_INTENT_TRACE cancel_generation=true input=never mind",
                "ADB_INTENT_TRACE cancel_generation=true input=$R",
            ),
            Arguments.of(
                "llm_tools_assistant_reply: Sure — I've added milk and bread to your shopping list.",
                "llm_tools_assistant_reply: $R",
            ),
            Arguments.of("DirectReply: Your next alarm is at 6:30 am.", "DirectReply: $R"),
            Arguments.of(
                "Skipping duplicate initial query after restore: what's on today",
                "Skipping duplicate initial query after restore: $R",
            ),
            Arguments.of(
                "Generating title for 42 with prompt: Summarise this chat about my divorce...",
                "Generating title for 42 with prompt: $R",
            ),
            Arguments.of("Auto-titled conversation 42: Divorce paperwork", "Auto-titled conversation 42: $R"),
            Arguments.of(
                "ADB_SLOT_STATE action=reply intent=send_sms slot=message reply=\"running late\"",
                "ADB_SLOT_STATE action=reply intent=send_sms slot=message reply=\"$R\"",
            ),
            Arguments.of(
                "llm_tools_legacy_tool: raw={\"name\":\"save_memory\",\"arguments\":{\"content\":\"I hate coriander\"}}",
                "llm_tools_legacy_tool: raw=$R",
            ),
            Arguments.of(
                "llm_tools_skill_result: skill={\"name\":\"run_intent\",\"arguments\":{\"intent_name\":\"add_to_li mode=success success=true",
                "llm_tools_skill_result: skill=$R",
            ),
            // List items and intent parameters
            Arguments.of(
                "NativeIntentHandler.handle: intent=add_to_list params={item=oat milk, list_name=shopping} source=quick",
                "NativeIntentHandler.handle: intent=add_to_list params=$R source=quick",
            ),
            Arguments.of(
                "event_seq: tool_call name=run_intent args={\"intent_name\":\"bulk_add_to_list\",\"parameters\":\"{\\\"items\\\":[\\\"eggs\\\"]}\"}",
                "event_seq: tool_call name=run_intent args=$R",
            ),
            Arguments.of(
                "ToolSet: runIntent(add_to_list, {\"item\":\"eggs\",\"list_name\":\"shopping\"})",
                "ToolSet: runIntent(add_to_list, $R)",
            ),
            Arguments.of(
                "event_seq: tool_result name=load_skill resultType=Success directReply=false returnedToGemma=true content=\"Shopping: eggs, \\\"free range\\\" milk\"",
                "event_seq: tool_result name=load_skill resultType=Success directReply=false returnedToGemma=true content=\"$R\"",
            ),
            // Memory text
            Arguments.of("ToolSet: saveMemory(My partner is called Sam)", "ToolSet: saveMemory($R)"),
            Arguments.of("ToolSet: searchMemory(partner name)", "ToolSet: searchMemory($R)"),
            Arguments.of(
                "SaveMemorySkill: stored core memory — 'I'm allergic to peanuts'",
                "SaveMemorySkill: stored core memory — '$R'",
            ),
            Arguments.of(
                "SearchMemorySkill: query='partner' conversationId=c9 results=3",
                "SearchMemorySkill: query='$R' conversationId=c9 results=3",
            ),
            Arguments.of(
                "Episodic distillation dropped ungrounded sentence: The user lives in Petone.",
                "Episodic distillation dropped ungrounded sentence: $R",
            ),
            // Intent classifier and contacts
            Arguments.of(
                "classify('call mum') -> make_call score=0.82",
                "classify('$R') -> make_call score=0.82",
            ),
            Arguments.of(
                "Alias resolved: 'mum' → 'Jane Doe' (+64 21 555 0199)",
                "Alias resolved: '$R' → '$R' ($R)",
            ),
            Arguments.of("Email resolved: 'Sam' → sam.smith@example.com", "Email resolved: '$R' → $R"),
            Arguments.of(
                "ToolSet: getWeather(location=Ōtaki Beach, forecastDays=3)",
                "ToolSet: getWeather($R)",
            ),
            Arguments.of(
                "Recipe generation success: sessionId=s1, dayIndex=2, title=Mum's lasagne",
                "Recipe generation success: sessionId=s1, dayIndex=2, title=$R",
            ),
        )

        @JvmStatic
        fun structuralMessages(): List<String> = listOf(
            "TTFT (Time to First Token): 2310ms [backend=GPU]",
            "Android native STT ready: sessionId=12 mode=COMMAND backend=ON_DEVICE",
            "Android native STT retried with platform recognizer after error=7 for sessionId=12 heardSpeech=true sawPartialTranscript=false",
            "Auto STT route=Zipformer mode=COMMAND allowOnlineFallback=false",
            "ADB_TOOL_COMPLETE commandId=abc123 intent=get_list_items result=failure_no_llm",
            "TTFT_STAGE commandId=abc123 stage=first_answer_token_visible t_ms=812",
            "thinking_save: thinkingLen=0, contentLen=214",
            "llm_tools_skill_result: tool=run_intent mode=success success=true",
            "Added core memory id=17 rowId=17 source=USER",
            "Core vec search: 4 raw results, distances=[0.412, 0.533, 0.601, 0.702]",
            "Generation complete: total=4123ms, TTFT=2310ms, tokens=88",
            "onTrimMemory level=15 — releasing inference session",
            "Engine ready — backend: GPU, maxTokens: 4096",
            "Available memory (3512 MB) below GPU minimum",
            "RunJsSkill: executing skill=jandal-dice args=",
            "ToolSet: loadSkill(run_intent)",
            "ToolSet: getSystemInfo()",
            "AndroidTextToSpeechController: bounded recovery requested reason=timeout.",
            "Download complete: /data/user/0/ai.jandal.assistant/files/models/gemma.litertlm",
            "Built: 2026-11-14 09:12:01 commit 3340e57b",
            "java.lang.IllegalStateException: engine closed",
            "\tat com.kernel.ai.core.inference.LiteRtInferenceEngine.generate(LiteRtInferenceEngine.kt:1512)",
            "--------- beginning of crash",
        )
    }
}
