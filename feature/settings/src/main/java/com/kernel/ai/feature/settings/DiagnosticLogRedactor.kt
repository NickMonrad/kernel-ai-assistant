package com.kernel.ai.feature.settings

/**
 * Removes user content from diagnostic exports (#1840).
 *
 * Exported logs leave the phone only when the user chooses to share them. Unless the user
 * opts in to include their chats and messages, the export is passed through this redactor,
 * which replaces what a user said or typed — and what the assistant replied — with
 * [REDACTED_MARKER] while keeping structural information (tags, timings, routes, intent
 * names, error codes, session IDs) intact.
 *
 * Fail-closed: once a message reaches a marker that starts user-derived content, everything
 * from that marker to the end of the logical message is redacted, and only the safe prefix
 * (tool or intent name, key, tag) is kept. Losing diagnostic detail is acceptable; leaking is not.
 * The markers are:
 * - tool calls whose arguments are content (`ToolSet: saveMemory(…)`, `ToolSet: runIntent(name, …)`);
 * - content keys (`params=`, `reply=`, `request=`, `responsePreview=`, payloads `raw=`/`skill=`);
 * - classifier inputs (`classify(…`), contact, alias and email resolution lines;
 * - the first quote that opens a span (`"`, `“`, or `'`/`‘` not inside a word such as `Sarah's`);
 * - lines whose remainder is content (`llm_tools_assistant_reply: …`, `DirectReply: …`);
 * - multi-line blocks that dump model output (`Profile LLM extraction succeeded:` + YAML);
 * - locations and account names (`fetched … for <place>`, `user=<name>`);
 * - email addresses and phone numbers anywhere.
 *
 * Rules run on a logical message: consecutive logcat lines with the same header are one message,
 * so a remainder that continues onto the next line is redacted to the end of the message.
 * Unquoted values of plain content keys end only at the specific next key the app emits (see
 * [VALUE_END_TOKENS]); when no known end exists they run to the end of the message.
 */
internal object DiagnosticLogRedactor {

    const val REDACTED_MARKER = "[redacted]"

    /** `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message` from `logcat -v threadtime`. */
    private val THREADTIME_LINE =
        Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+.*?: )(.*)$""")

    private val EMAIL = Regex("""[\p{L}\p{N}._%+'-]+@[\p{L}\p{N}-]+(?:\.[\p{L}\p{N}-]+)+""")

    /**
     * Phone-number-like digit runs (8–15 digits, optional `+`, separators between digits: `(09) 123-4567`,
     * `+1 (555) 123-4567`, `+64 21 - 123 4567`, `021 – 123 4567`). No `.` separator, so decimals such as
     * `1234.5678` and timestamps never form a run. A run may follow `=` or `:` (`number=…`, `tel:…`).
     */
    private val PHONE = Regex("""(?<![\w.\-/])(?!\d{4}-\d{2}-\d{2})\+?\d(?:(?: [-–] |[ \-–()]{0,2})\d){7,14}(?![\w.])""")

    /** A bare digit run followed by a unit is a size or duration (`12345678 bytes`, `1234 ms`), not a phone number. */
    private val BARE_DIGITS = Regex("""\+?\d+""")
    private val SIZE_UNIT = Regex("""\s*(?:bytes|KB|MB|GB|ms)\b""")

    /** Weather cache keys `loc:<place>:(weekend|tomorrow|forecast:N|current)`: the place is content. */
    private val WEATHER_CACHE_LOCATION = Regex("""\bloc:.+?(?=:(?:weekend|tomorrow|forecast:\d+|current)\b)""")

    /** Keys whose value is user content regardless of shape. */
    private val CONTENT_KEYS = setOf(
        "input", "transcript", "partial", "text", "query", "reply", "prompt", "content", "raw",
        "title", "dayTitle", "params", "parameters", "args", "utterance", "normalized",
        "expanded", "recovered", "location", "item", "items", "memory", "fact", "sentence",
        "snippet", "definition", "term", "user",
    )

    /** Keys whose value is content only when it is a JSON/map payload (e.g. `skill={…}`). */
    private val PAYLOAD_ONLY_KEYS = setOf("skill")

    /**
     * Tokens the app emits right after an unquoted content value. A value ends at the first of these
     * (so `input=remind me x=5 …` is not cut at `x=5`); with none present it runs to the end of the
     * message. Only tokens the code actually emits belong here.
     */
    private val VALUE_END_TOKENS = mapOf(
        "input" to listOf(" submitMode="),
        "transcript" to listOf(" mode=", " language=", " submitMode="),
        "snippet" to listOf(" | rank="),
        "term" to listOf(" src="),
    )

    private val KEY = Regex("""(?<![\w.])([A-Za-z_][\w]*)=""")

    /**
     * Markers after which the rest of the logical message is user or assistant content. Each keeps the
     * safe prefix (group 1) and drops everything after it. Order matters only where one rule's output
     * would feed another.
     */
    private val REST_OF_MESSAGE = listOf(
        Regex("""(?s)(llm_tools_assistant_reply: ).*"""),
        Regex("""(?s)(verbose_\w+: ).*"""),
        Regex("""(?s)(llm_tools_native_tool: .*? request=).*"""),
        Regex("""(?s)(llm_tools_legacy_tool: .*? raw=).*"""),
        Regex("""(?s)(DirectReply: ).*"""),
        Regex("""(?s)(Skipping duplicate initial query after restore: ).*"""),
        Regex("""(?s)(Auto-titled conversation \S+: ).*"""),
        Regex("""(?s)(with prompt: ).*"""),
        Regex("""(?s)(dropped ungrounded sentence: ).*"""),
        Regex("""(?s)(conversation title for \S+ to ).*"""),
        Regex("""(?s)(SlotValidation: blocked \S+ — ).*"""),
        // Account name (HuggingFaceAuthManager).
        Regex("""(?s)(HF auth success — user: ).*"""),
        // Places (GetWeatherSkill). The place is the last thing on these lines.
        Regex("""(?s)(GetWeatherSkill: fetched \d+-day forecast for ).*"""),
        Regex("""(?s)(GetWeatherSkill: fetched forecast for day \d+ \([^)\n]*\) for ).*"""),
        Regex("""(?s)(GetWeatherSkill: fetched weather for ).*"""),
        // org.json parse errors echo the raw parameters ("... at character 31 of {…}").
        Regex("""(?s)( at character \d+ of ).*"""),
        // Deliberately fail-closed across lines: a `Value … of type` error can wrap onto later lines
        // of the same message, so everything from `Value ` to the end of the message is dropped.
        Regex("""(?s)(\bValue ).*? of type.*"""),
        // Content-carrying keys: the value and everything after it is content.
        Regex("""(?s)((?<![\w.])(?:params|reply|request|responsePreview)=).*"""),
        // Geocoding errors: only the query line is redacted; a throwable's stack trace stays.
        Regex("""(Geocoding failed for: )[^\n]*"""),
        Regex("""(Country capital lookup failed for: )[^\n]*"""),
        // Contact, alias and email resolution: the name and number are content.
        Regex("""(?s)(Contact resolved: ).*"""),
        Regex("""(?s)(Alias resolved: ).*"""),
        Regex("""(?s)(Self-referential contact ).*"""),
        Regex("""(?s)(Email resolved(?: via alias)?:? ).*"""),
        Regex("""(?s)(ToolSet: (?:saveMemory|searchMemory|queryWikipedia|getWeather|runJs)\().*"""),
        // `runIntent(intent_name, {json})`: keep the intent name, drop the parameters.
        Regex("""(?s)(ToolSet: runIntent\([^,\n()]*, ).*"""),
        // A runIntent call without a `name, ` prefix: keep only the tool name.
        Regex("""(?s)(ToolSet: runIntent\()(?![^,\n()]*, ).*"""),
        // Classifier input is the user's text.
        Regex("""(?s)((?<![\w.])classify\().*"""),
    )

    /** Messages that continue with a multi-line dump of extracted or model content. */
    private val BLOCK_DUMP = Regex(
        """(?s)((?:Profile LLM extraction succeeded|Profile regex fallback|Raw response):)\n.*""",
    )

    /**
     * Redacts a `logcat -v threadtime` capture. Consecutive lines that share a header
     * (same time, PID, TID, level and tag) are one multi-line log message, so they are
     * redacted together and re-emitted with that header.
     */
    fun redactLogcat(logcat: String): String {
        val out = mutableListOf<String>()
        var groupHeader: String? = null
        val groupLines = mutableListOf<String>()

        fun flush() {
            val header = groupHeader ?: return
            redactMessage(groupLines.joinToString("\n")).split('\n').forEach { out += header + it }
            groupHeader = null
            groupLines.clear()
        }

        for (line in logcat.split('\n')) {
            val match = THREADTIME_LINE.matchEntire(line)
            if (match == null) {
                flush()
                // Non-entry lines (buffer headings, stray output) are still scrubbed.
                out += redactMessage(line)
                continue
            }
            val header = match.groupValues[1]
            if (header != groupHeader) flush()
            groupHeader = header
            groupLines += match.groupValues[2]
        }
        flush()
        return out.joinToString("\n")
    }

    /** Redacts free text (an exception record, a trace block): treated as one logical message. */
    fun redactText(text: String): String = redactMessage(text)

    /**
     * Scrubs an ANR trace excerpt with emails and phone numbers only. Thread and frame lines contain
     * quotes and `=` that the full redactor treats as content, which would cut the trace short.
     */
    fun redactTraceText(text: String): String = redactPhones(EMAIL.replace(text) { REDACTED_MARKER })

    /** Redacts one logical log message, which may span several lines. */
    fun redactMessage(message: String): String {
        if (message.isEmpty()) return message
        var result = BLOCK_DUMP.replace(message) { "${it.groupValues[1]}\n$REDACTED_MARKER" }
        REST_OF_MESSAGE.forEach { rule ->
            result = rule.replace(result) { "${it.groupValues[1]}$REDACTED_MARKER" }
        }
        result = WEATHER_CACHE_LOCATION.replace(result, "loc:$REDACTED_MARKER")
        result = redactKeyValues(result)
        result = redactFromFirstQuote(result)
        result = EMAIL.replace(result, REDACTED_MARKER)
        result = redactPhones(result)
        return result
    }

    /**
     * Fail-closed quote rule: the first quote that opens a span (`"`, `“`, or `'`/`‘` not inside a
     * word, so `Sarah's` does not open one) keeps the quote and drops the rest of the message.
     */
    private fun redactFromFirstQuote(text: String): String {
        for (i in text.indices) {
            val opens = when (text[i]) {
                '"', '“' -> true
                '\'', '‘' -> i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_')
                else -> false
            }
            if (opens) return text.substring(0, i + 1) + REDACTED_MARKER
        }
        return text
    }

    /** Replaces phone-number-like runs, except bare digit runs followed by a size or duration unit. */
    private fun redactPhones(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        for (match in PHONE.findAll(text)) {
            val end = match.range.last + 1
            val sizeOrDuration = BARE_DIGITS.matches(match.value) &&
                SIZE_UNIT.find(text, end)?.range?.first == end
            if (sizeOrDuration) continue
            out.append(text, cursor, match.range.first).append(REDACTED_MARKER)
            cursor = end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** Index of the first [VALUE_END_TOKENS] entry for [key] at or after [from], or -1. */
    private fun endTokenIndex(text: String, from: Int, key: String): Int =
        VALUE_END_TOKENS[key].orEmpty().map { text.indexOf(it, from) }.filter { it >= 0 }.minOrNull() ?: -1

    /** Replaces unquoted values of [CONTENT_KEYS] and payload values (`raw={…}`, `skill={…}`). */
    private fun redactKeyValues(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            val match = KEY.find(text, cursor) ?: break
            val key = match.groupValues[1]
            val valueStart = match.range.last + 1
            val first = text.getOrNull(valueStart)
            val isPayload = first == '{' || first == '['
            val redact = first != null && first != '"' && first != '\'' && first != '‘' && first != '“' &&
                first != '\n' && (key in CONTENT_KEYS || (key in PAYLOAD_ONLY_KEYS && isPayload))
            if (!redact) {
                out.append(text, cursor, valueStart)
                cursor = valueStart
                continue
            }
            // Payloads run to the end of the message: a bracket inside the text cannot end them early.
            val valueEnd = if (isPayload) text.length else unquotedEnd(text, valueStart, key)
            out.append(text, cursor, valueStart).append(REDACTED_MARKER)
            cursor = valueEnd
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** End of an unquoted value: its key's next known token, or the end of the message. */
    private fun unquotedEnd(text: String, start: Int, key: String): Int {
        val end = endTokenIndex(text, start, key)
        return if (end >= 0) end else text.length
    }
}
