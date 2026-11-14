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
            11-14 09:12:01.100  4321  4400 I NativeVoiceInput: Android native STT result: sessionId=7 mode=COMMAND transcript='$r
            11-14 09:12:01.120  4321  4400 W TranscriptNormaliser: MISHEARD_ADD applied to "$r
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
        assertEquals("11-14 09:14:00.000  4321  4321 D KernelAI: Raw title output: \"$r", redacted)
    }

    @Test
    fun `redactText scrubs user text in an exception message and drops what follows it`() {
        val record = """
            Exception: java.lang.NumberFormatException
            Message: For input string: "call Mum at 5"
            Stack trace:
            	at java.lang.Integer.parseInt(Integer.java:747)
        """.trimIndent()

        val redacted = DiagnosticLogRedactor.redactText(record)

        assertFalse(redacted.contains("Mum"))
        assertTrue(redacted.contains("Message: For input string: \"$r"))
        assertFalse(redacted.contains("Integer.java"))
    }

    @Test
    fun `redacts a DirectReply whose notes continue onto later logcat lines`() {
        val logcat = """
            11-14 09:20:00.000  4321  4321 D KernelAI: DirectReply: Your notes:
            11-14 09:20:00.000  4321  4321 D KernelAI: - Buy eggs
            11-14 09:20:00.000  4321  4321 D KernelAI: - Call the clinic about my biopsy
        """.trimIndent()

        assertEquals("11-14 09:20:00.000  4321  4321 D KernelAI: DirectReply: $r", DiagnosticLogRedactor.redactLogcat(logcat))
    }

    @Test
    fun `redacts a list item value that continues onto later logcat lines`() {
        val logcat = """
            11-14 09:21:00.000  4321  4321 D ListsViewModel: created list items=eggs
            11-14 09:21:00.000  4321  4321 D ListsViewModel: - oat milk
        """.trimIndent()

        assertEquals(
            "11-14 09:21:00.000  4321  4321 D ListsViewModel: created list items=$r",
            DiagnosticLogRedactor.redactLogcat(logcat),
        )
    }

    @Test
    fun `redacts a memory that continues onto later logcat lines`() {
        val logcat = """
            11-14 09:22:00.000  4321  4321 D KernelAI: ToolSet: saveMemory(My partner is called Sam
            11-14 09:22:00.000  4321  4321 D KernelAI: and I'm allergic to peanuts)
        """.trimIndent()

        assertEquals(
            "11-14 09:22:00.000  4321  4321 D KernelAI: ToolSet: saveMemory($r",
            DiagnosticLogRedactor.redactLogcat(logcat),
        )
    }

    @Test
    fun `redacts an input value that continues onto later logcat lines and keeps the submit mode`() {
        val logcat = """
            11-14 09:23:00.000  4321  4321 D KernelAI: ADB_INTENT_TRACE commandId=c42 input=remind me
            11-14 09:23:00.000  4321  4321 D KernelAI: my PIN is 1234 submitMode=TEXT
        """.trimIndent()

        assertEquals(
            "11-14 09:23:00.000  4321  4321 D KernelAI: ADB_INTENT_TRACE commandId=c42 input=$r submitMode=TEXT",
            DiagnosticLogRedactor.redactLogcat(logcat),
        )
    }

    @Test
    fun `redacts a params payload whose message continues onto a later logcat line`() {
        val logcat = """
            11-14 09:24:00.000  4321  4321 D KernelAI: NativeIntentHandler.handle: intent=send_sms params={to=Sam, message=hi
            11-14 09:24:00.000  4321  4321 D KernelAI: I'm at the clinic} source=quick
        """.trimIndent()

        assertEquals(
            "11-14 09:24:00.000  4321  4321 D KernelAI: NativeIntentHandler.handle: intent=send_sms params=$r",
            DiagnosticLogRedactor.redactLogcat(logcat),
        )
    }

    @Test
    fun `redacts a geocoding query but keeps the stack trace of the same logcat entry`() {
        val logcat = """
            11-14 09:25:00.000  4321  4400 W GetWeatherSkill: Geocoding failed for: 12 Smith Street
            11-14 09:25:00.000  4321  4400 W GetWeatherSkill: java.io.IOException: timeout
        """.trimIndent()

        assertEquals(
            """
            11-14 09:25:00.000  4321  4400 W GetWeatherSkill: Geocoding failed for: $r
            11-14 09:25:00.000  4321  4400 W GetWeatherSkill: java.io.IOException: timeout
            """.trimIndent(),
            DiagnosticLogRedactor.redactLogcat(logcat),
        )
    }

    companion object {
        private const val R = REDACTED_MARKER
        private const val r = REDACTED_MARKER

        @JvmStatic
        fun redactedMessages(): List<Arguments> = listOf(
            // Values end at the next key the app emits, or at the end of the message.
            Arguments.of(
                "ADB_INTENT_TRACE commandId=c1 input=remind me x=5 to call my lawyer about divorce",
                "ADB_INTENT_TRACE commandId=c1 input=$R",
            ),
            Arguments.of(
                "VoiceCommandService: handling transcript=\"text mum \"running late\" ok see you soon\" mode=WIDGET",
                "VoiceCommandService: handling transcript=\"$r",
            ),
            Arguments.of(
                "Android native STT result: sessionId=7 mode=COMMAND transcript=\"text mum \"running late\" ok see you soon\"",
                "Android native STT result: sessionId=7 mode=COMMAND transcript=\"$r",
            ),
            // RAG candidate lines: each value ends at the next field the app emits.
            Arguments.of(
                "rank=0 term=Otaki src=core score=0.91 dist=0.090 access=1731 snippet=[Otaki] Beach is my home | rank=1 term=mum src=core score=0.50 dist=0.500 access=1700 snippet=[mum] Jane Doe",
                "rank=0 term=$r src=core score=0.91 dist=0.090 access=1731 snippet=$r",
            ),
            Arguments.of(
                "Distilled candidates (1): rank=0 conv=42 src=episodic score=0.8 dist=0.200 snippet=Chat about divorce paperwork",
                "Distilled candidates (1): rank=0 conv=42 src=episodic score=0.8 dist=0.200 snippet=$R",
            ),
            Arguments.of(
                "Core candidate: definition=Likes flat whites in the morning",
                "Core candidate: definition=$R",
            ),
            // Account names and locations.
            Arguments.of(
                "HuggingFaceAuthManager: restored auth=true, user=sam-jones",
                "HuggingFaceAuthManager: restored auth=true, user=$R",
            ),
            Arguments.of("HF auth success — user: sam-jones", "HF auth success — user: $R"),
            Arguments.of(
                "GetWeatherSkill: fetched 7-day forecast for Bundaberg Central",
                "GetWeatherSkill: fetched 7-day forecast for $R",
            ),
            Arguments.of(
                "GetWeatherSkill: fetched forecast for day 2 (Tue 17 Nov) for Otaki Beach",
                "GetWeatherSkill: fetched forecast for day 2 (Tue 17 Nov) for $R",
            ),
            Arguments.of(
                "GetWeatherSkill: fetched weather for 12 Smith Street, Petone",
                "GetWeatherSkill: fetched weather for $R",
            ),
            Arguments.of("Geocoding failed for: 12 Smith Street", "Geocoding failed for: $R"),
            Arguments.of(
                "Country capital lookup failed for: Narnia",
                "Country capital lookup failed for: $R",
            ),
            // Request and response payloads (ChatViewModel, MealPlannerCoordinator): trailing, so the rest is content.
            Arguments.of(
                "llm_tools_native_tool: tool=save_memory request={\"text\":\"my sister Jane lives in Wanaka\"",
                "llm_tools_native_tool: tool=save_memory request=$R",
            ),
            Arguments.of(
                "llm_tools_native_tool: tool=save_memory request=my sister Jane lives in Wanaka",
                "llm_tools_native_tool: tool=save_memory request=$R",
            ),
            Arguments.of(
                "Plan generation failed: sessionId=s9, attempt=1/2, errorCode=PLAN_JSON_INVALID, responsePreview={\"days\":[{\"name\":\"Aroha lasagne\"",
                "Plan generation failed: sessionId=s9, attempt=1/2, errorCode=PLAN_JSON_INVALID, responsePreview=$R",
            ),
            // Weather cache keys carry the place (GetWeatherSkill); the cache shape stays.
            Arguments.of(
                "Weather cache stored: loc:Wanaka:forecast:3 (512 bytes)",
                "Weather cache stored: loc:$R:forecast:3 (512 bytes)",
            ),
            Arguments.of(
                "Raw cache read failed for key: loc:Palmerston North:weekend",
                "Raw cache read failed for key: loc:$R:weekend",
            ),
            Arguments.of(
                "Cache read failed for key: loc:Taupō:current",
                "Cache read failed for key: loc:$R:current",
            ),
            // Formatted phone numbers (NativeIntentHandler): up to two separators between digits.
            Arguments.of("Dialing contact (09) 123-4567 now", "Dialing contact ($R now"),
            Arguments.of("Dialing contact +1 (555) 123-4567", "Dialing contact $R"),
            Arguments.of("Dialing contact 09 123 4567 now", "Dialing contact $R now"),
            // STT transcripts and partials
            Arguments.of(
                "Android native STT partial: sessionId=12 mode=COMMAND transcript='what's the weather in Taupō'",
                "Android native STT partial: sessionId=12 mode=COMMAND transcript='$r",
            ),
            Arguments.of(
                "KIWI_PHONETIC applied to \"set a time a for ten\" → \"set a timer for ten\"",
                "KIWI_PHONETIC applied to \"$r",
            ),
            Arguments.of(
                "VoiceCommandService: handling transcript=\"text Sam I'm late\" mode=WIDGET",
                "VoiceCommandService: handling transcript=\"$r",
            ),
            Arguments.of(
                "ActionsViewModel: normalized voice command \"ad eggs\" -> \"add eggs\"",
                "ActionsViewModel: normalized voice command \"$r",
            ),
            Arguments.of(
                "Stop command intercepted in back-and-forth loop: \"stop\"",
                "Stop command intercepted in back-and-forth loop: \"$r",
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
                "ADB_SLOT_STATE action=reply intent=send_sms slot=message reply=$r",
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
                "NativeIntentHandler.handle: intent=add_to_list params=$r",
            ),
            Arguments.of(
                "event_seq: tool_call name=run_intent args={\"intent_name\":\"bulk_add_to_list\",\"parameters\":\"{\\\"items\\\":[\\\"eggs\\\"]}\"}",
                "event_seq: tool_call name=run_intent args=$R",
            ),
            Arguments.of(
                "ToolSet: runIntent(add_to_list, {\"item\":\"eggs\",\"list_name\":\"shopping\"})",
                "ToolSet: runIntent(add_to_list, $r",
            ),
            Arguments.of(
                "event_seq: tool_result name=load_skill resultType=Success directReply=false returnedToGemma=true content=\"Shopping: eggs, \\\"free range\\\" milk\"",
                "event_seq: tool_result name=load_skill resultType=Success directReply=false returnedToGemma=true content=\"$r",
            ),
            // Memory text
            Arguments.of("ToolSet: saveMemory(My partner is called Sam)", "ToolSet: saveMemory($r"),
            Arguments.of("ToolSet: searchMemory(partner name)", "ToolSet: searchMemory($r"),
            Arguments.of(
                "SaveMemorySkill: stored core memory — 'I'm allergic to peanuts'",
                "SaveMemorySkill: stored core memory — '$r",
            ),
            Arguments.of(
                "SearchMemorySkill: query='partner' conversationId=c9 results=3",
                "SearchMemorySkill: query='$r",
            ),
            Arguments.of(
                "Episodic distillation dropped ungrounded sentence: The user lives in Petone.",
                "Episodic distillation dropped ungrounded sentence: $R",
            ),
            // Intent classifier and contacts
            Arguments.of(
                "classify('call mum') -> make_call score=0.82",
                "classify($r",
            ),
            Arguments.of(
                "Alias resolved: 'mum' → 'Jane Doe' (+64 21 555 0199)",
                "Alias resolved: $r",
            ),
            Arguments.of("Email resolved: 'Sam' → sam.smith@example.com", "Email resolved: $r"),
            Arguments.of(
                "ToolSet: getWeather(location=Ōtaki Beach, forecastDays=3)",
                "ToolSet: getWeather($r",
            ),
            Arguments.of(
                "Recipe generation success: sessionId=s1, dayIndex=2, title=Mum's lasagne",
                "Recipe generation success: sessionId=s1, dayIndex=2, title=$R",
            ),
            // Brackets end only where their own type closes: smileys and brackets in text stay inside the map.
            Arguments.of(
                "NativeIntentHandler.handle: intent=send_sms params={contact=Mum, message=sorry :) see you at 5 at Dr Patel} source=quick",
                "NativeIntentHandler.handle: intent=send_sms params=$r",
            ),
            Arguments.of(
                "NativeIntentHandler.handle: intent=send_sms params={contact=Mum, message=ok (see you) [later]} source=quick",
                "NativeIntentHandler.handle: intent=send_sms params=$r",
            ),
            Arguments.of(
                "NativeIntentHandler.handle: intent=send_sms params={a={b=1} c=2} source=quick",
                "NativeIntentHandler.handle: intent=send_sms params=$r",
            ),
            // A truncated map is redacted to the end of the message.
            Arguments.of(
                "NativeIntentHandler.handle: intent=send_sms params={contact=Mum, message=sorry :) see you at 5",
                "NativeIntentHandler.handle: intent=send_sms params=$R",
            ),
            // Inner quotes and apostrophes stay inside the span; it closes at the last quote before the delimiter.
            Arguments.of(
                "classify('the kids' party at Sarah's place') -> birthday score=0.6",
                "classify($r",
            ),
            Arguments.of(
                "ADB_SLOT_STATE action=reply intent=send_sms slot=message reply=\"he said \"hi\" and left early\"",
                "ADB_SLOT_STATE action=reply intent=send_sms slot=message reply=$r",
            ),
            // An unmatched opening quote is redacted to the end of the message.
            Arguments.of(
                "Speech parse failed: said \"running late to see you",
                "Speech parse failed: said \"$R",
            ),
            Arguments.of(
                "ToolSet: runIntent params not valid JSON — failing closed: Unterminated string at 31 {\"item\":\"egg",
                "ToolSet: runIntent params not valid JSON — failing closed: Unterminated string at 31 {\"$r",
            ),
            Arguments.of("Speech says 'hello there", "Speech says '$R"),
            // Contact resolution: the number is content, whatever its shape; the name is quoted.
            Arguments.of("Contact resolved: 'Mum' → 021 1234", "Contact resolved: $r"),
            Arguments.of("Contact resolved: 'Sam' → 1234567", "Contact resolved: $r"),
            Arguments.of("Contact resolved: 'Sam' → +64 21 - 123 4567", "Contact resolved: $r"),
            Arguments.of("Dialing contact +64 21 - 123 4567", "Dialing contact $R"),
            // Fail-closed: a marker drops everything after it, so smileys, stray brackets, inner quotes,
            // arrows, newlines and 7-digit numbers in alias or self-referential lines cannot leak.
            Arguments.of(
                "ToolSet: saveMemory(I love sushi :) my address is 12 Smith St)",
                "ToolSet: saveMemory($r",
            ),
            Arguments.of(
                "ToolSet: runIntent(send_sms, {\"contact\":\"Mum\",\"message\":\"hi :) see you at Dr Patel\"})",
                "ToolSet: runIntent(send_sms, $r",
            ),
            Arguments.of("ToolSet: searchMemory(where is Sarah's place :) on Queen St)", "ToolSet: searchMemory($r"),
            Arguments.of("ToolSet: runJs(params={\"text\":\"ok :) Sarah lives at 4 Elm Rd\"})", "ToolSet: runJs($r"),
            Arguments.of("ToolSet: getWeather(location=Dunedin :) Mosgiel, forecastDays=3)", "ToolSet: getWeather($r"),
            Arguments.of(
                "NativeIntentHandler.handle: intent=send_sms params={contact=Mum, message=see you } at 12 Smith St}",
                "NativeIntentHandler.handle: intent=send_sms params=$r",
            ),
            Arguments.of(
                "NativeIntentHandler.handle: intent=send_sms params=[a, b ] c] d",
                "NativeIntentHandler.handle: intent=send_sms params=$r",
            ),
            Arguments.of("ActionsViewModel: reply=\"I said \"ok\" :) see Sarah at 5\"", "ActionsViewModel: reply=$r"),
            Arguments.of("ActionsViewModel: reply=\"he said \"hi\" → bye Sarah\"", "ActionsViewModel: reply=$r"),
            Arguments.of("ActionsViewModel: heard \"ok :) tell \"Sarah\" hi\"", "ActionsViewModel: heard \"$r"),
            Arguments.of("ActionsViewModel: reply=\"I said \"hi\"\nmeet at 12 Smith St\"", "ActionsViewModel: reply=$r"),
            Arguments.of("classify('meet at Jo's :) house on Smith St') -> chat", "classify($r"),
            Arguments.of("Alias resolved: 'mum' → 'Jane Smith' (309 1234)", "Alias resolved: $r"),
            Arguments.of("Alias resolved: 'mum' → 'Jane Smith' (309-1234)", "Alias resolved: $r"),
            Arguments.of("Self-referential contact 'me' → own number: 3091234", "Self-referential contact $r"),
            Arguments.of("Email resolved: 'Zoë' → zoë@example.com", "Email resolved: $r"),
            Arguments.of("Email resolved: 'Jane' → jane.o'neil@example.com", "Email resolved: $r"),
            Arguments.of("Lookup email zoë@example.com now", "Lookup email $r now"),
            Arguments.of("Lookup email jane.o'neil@example.com now", "Lookup email $r now"),
            Arguments.of("Phone lookup number=0211234567 ok", "Phone lookup number=$r ok"),
            Arguments.of("Dialing tel:0211234567 now", "Dialing tel:$r now"),
            Arguments.of("Dialing 021 – 123 4567 now", "Dialing $r now"),
            Arguments.of("Latency 021 123 4567 ms", "Latency $r ms"),
            // Weather retry labels: the place name is content and everything after the label prefix goes.
            Arguments.of("Weekend Forecast API (Petone, Lower Hutt) failed after 3 retries", "Weekend Forecast API ($r"),
            Arguments.of("Forecast API (Petone, Lower Hutt, 3 days) failed (attempt 1/3)", "Forecast API ($r"),
            Arguments.of("Forecast API (day 2 of Petone) failed (attempt 1/3)", "Forecast API ($r"),
            Arguments.of("Weather API (Petone, Lower Hutt) failed after 3 retries", "Weather API ($r"),
            // org.json parse errors echo the raw parameters.
            Arguments.of(
                "ToolSet: runIntent params not valid JSON: Unterminated string at character 31 of {\"item\":\"egg",
                "ToolSet: runIntent params not valid JSON: Unterminated string at character 31 of $r",
            ),
            Arguments.of(
                "ToolSet: runJs failed: Value {\"item\":\"egg\"} of type org.json.JSONObject cannot be a string",
                "ToolSet: runJs failed: Value $r",
            ),
        )

        @JvmStatic
        fun structuralMessages(): List<String> = listOf(
            "HuggingFaceAuthManager: restored auth=true",
            "RagRepository: rank=0 score=0.91 dist=0.090 access=1731",
            "GetWeatherSkill: fetched 7-day forecast",
            "ADB_INTENT_TRACE commandId=c42 submitMode=VOICE",
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
            "Timer fired at 2026-11-14 09:12:01.123 id=17 frame=1234.5678",
            "Shown 11-14 09:20:00.000 at offset 4321 of 8192",
            // Byte counts, sizes and durations keep their digits, including 7-digit and unit-suffixed runs.
            "Download complete: 1234567 bytes in 830ms",
            "Cache size 12345678 bytes",
            "Waited 12345678 ms before retry",
            "Export written 2026-11-14 09:20:00.000 size=1048576 bytes",
            "Generation complete: total=12345678ms",
        )
    }
}
