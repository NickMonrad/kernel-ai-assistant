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
 * The rules mirror how the app's own log statements carry user text:
 * - quoted spans (`transcript='…'`, `applied to "…" → "…"`, `'$name'`);
 * - values of known content keys (`input=…`, `params={…}`, `args=…`, `title=…`, `snippet=…`);
 * - lines whose remainder is content (`llm_tools_assistant_reply: …`, `DirectReply: …`);
 * - tool calls whose arguments are content (`ToolSet: saveMemory(…)`);
 * - multi-line blocks that dump model output (`Profile LLM extraction succeeded:` + YAML);
 * - locations and account names (`fetched … for <place>`, `user=<name>`);
 * - email addresses and phone numbers anywhere.
 *
 * Rules run on a logical message: consecutive logcat lines with the same header are one message,
 * so a value or remainder that continues onto the next line is redacted to the end of the message.
 * Unquoted values end only at the specific next key the app emits (see [VALUE_END_TOKENS]); when
 * no known end exists they run to the end of the message. This is pattern-based and may be
 * incomplete; when in doubt it redacts more, never less.
 */
internal object DiagnosticLogRedactor {

    const val REDACTED_MARKER = "[redacted]"

    /** `MM-DD HH:MM:SS.mmm  PID  TID L TAG: message` from `logcat -v threadtime`. */
    private val THREADTIME_LINE =
        Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}\s+\d+\s+\d+\s+[VDIWEF]\s+.*?: )(.*)$""")

    /** A quoted span; the closing quote must not be followed by a word character. */
    private val DOUBLE_QUOTED = Regex("""(?s)"(?:\\.|.)*?"(?!\w)""")
    private val SINGLE_QUOTED = Regex("""(?s)(?<!\w)'(?:\\.|.)*?'(?!\w)""")
    private val SMART_DOUBLE_QUOTED = Regex("""(?s)“.*?”""")
    private val SMART_SINGLE_QUOTED = Regex("""(?s)(?<!\w)‘.*?’(?!\w)""")

    private val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

    /** Phone-number-like digit runs (8–15 digits, optional `+` and separators). */
    private val PHONE = Regex("""(?<![\w.=:/\-])(?!\d{4}-\d{2}-\d{2})\+?\d(?:[ \-()]?\d){7,14}(?![\w.])""")

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
     * Tokens the app emits right after a content value. A value ends at the first of these
     * (so `input=remind me x=5 …` is not cut at `x=5`); with none present it runs to the end
     * of the message. Only tokens the code actually emits belong here.
     */
    private val VALUE_END_TOKENS = mapOf(
        "input" to listOf(" submitMode="),
        "transcript" to listOf(" mode=", " language=", " submitMode="),
        "snippet" to listOf(" | rank="),
        "term" to listOf(" src="),
    )

    private val KEY = Regex("""(?<![\w.])([A-Za-z_][\w]*)=""")

    /** `transcript='…'` / `transcript="…"`: the quote is the last one before the next known key. */
    private val TRANSCRIPT_OPEN = Regex("""(?<![\w.])transcript=(["'])""")

    /** Markers after which the rest of the logical message is user or assistant content. */
    private val REST_OF_MESSAGE = listOf(
        Regex("""(?s)(llm_tools_assistant_reply: ).*"""),
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
        // Geocoding errors: only the query line is redacted; a throwable's stack trace stays.
        Regex("""(Geocoding failed for: )[^\n]*"""),
        Regex("""(Country capital lookup failed for: )[^\n]*"""),
    )

    /** Tool calls whose whole argument list is content. */
    private val TOOLSET_CONTENT_CALL =
        Regex("""ToolSet: (?:saveMemory|searchMemory|queryWikipedia|getWeather|runJs)\(""")

    /** `runIntent(intent_name, {json})`: keep the intent name, drop the parameters. */
    private val TOOLSET_RUN_INTENT = Regex("""ToolSet: runIntent\([^,\n)]*, """)

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

    /** Redacts one logical log message, which may span several lines. */
    fun redactMessage(message: String): String {
        if (message.isEmpty()) return message
        var result = BLOCK_DUMP.replace(message) { "${it.groupValues[1]}\n$REDACTED_MARKER" }
        result = redactCallArguments(result, TOOLSET_RUN_INTENT)
        result = redactCallArguments(result, TOOLSET_CONTENT_CALL)
        REST_OF_MESSAGE.forEach { rule ->
            result = rule.replace(result) { "${it.groupValues[1]}$REDACTED_MARKER" }
        }
        result = redactQuotedTranscripts(result)
        result = DOUBLE_QUOTED.replace(result, "\"$REDACTED_MARKER\"")
        result = SINGLE_QUOTED.replace(result, "'$REDACTED_MARKER'")
        result = SMART_DOUBLE_QUOTED.replace(result, "“$REDACTED_MARKER”")
        result = SMART_SINGLE_QUOTED.replace(result, "‘$REDACTED_MARKER’")
        result = redactKeyValues(result)
        result = EMAIL.replace(result, REDACTED_MARKER)
        result = PHONE.replace(result, REDACTED_MARKER)
        return result
    }

    /** Index of the first [VALUE_END_TOKENS] entry for [key] at or after [from], or -1. */
    private fun endTokenIndex(text: String, from: Int, key: String): Int =
        VALUE_END_TOKENS[key].orEmpty().map { text.indexOf(it, from) }.filter { it >= 0 }.minOrNull() ?: -1

    /**
     * `transcript='…'` and `transcript="…"` values may contain quotes of their own, so the
     * closing quote is the last one before the next known key (or the message's final quote).
     */
    private fun redactQuotedTranscripts(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        for (match in TRANSCRIPT_OPEN.findAll(text)) {
            val contentStart = match.range.last + 1
            if (contentStart < cursor) continue
            val quote = match.groupValues[1][0]
            val endToken = endTokenIndex(text, contentStart, "transcript")
            val contentEnd = when {
                endToken >= 0 -> text.lastIndexOf(quote, endToken - 1).takeIf { it >= contentStart } ?: endToken
                text.length - 1 >= contentStart && text.last() == quote -> text.length - 1
                else -> text.length
            }
            out.append(text, cursor, contentStart).append(REDACTED_MARKER)
            cursor = contentEnd
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** Replaces the arguments of each call matched by [callStart] (which ends just inside the `(`). */
    private fun redactCallArguments(text: String, callStart: Regex): String {
        val out = StringBuilder()
        var cursor = 0
        for (match in callStart.findAll(text)) {
            val argsStart = match.range.last + 1
            if (argsStart < cursor) continue
            out.append(text, cursor, argsStart).append(REDACTED_MARKER)
            cursor = closingParen(text, argsStart)
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** Index of the `)` closing a call whose arguments start at [from]; the message end if unclosed. */
    private fun closingParen(text: String, from: Int): Int {
        var depth = 1
        for (i in from until text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return text.length
    }

    /** Replaces unquoted values of [CONTENT_KEYS] (and payload values of [PAYLOAD_ONLY_KEYS]). */
    private fun redactKeyValues(text: String): String {
        val out = StringBuilder()
        var cursor = 0
        while (cursor < text.length) {
            val match = KEY.find(text, cursor) ?: break
            val key = match.groupValues[1]
            val valueStart = match.range.last + 1
            val first = text.getOrNull(valueStart)
            val isPayload = first == '{' || first == '['
            val redact = first != null && first != '"' && first != '\'' && first != '\n' &&
                (key in CONTENT_KEYS || (key in PAYLOAD_ONLY_KEYS && isPayload))
            if (!redact) {
                out.append(text, cursor, valueStart)
                cursor = valueStart
                continue
            }
            val valueEnd = if (isPayload && key !in VALUE_END_TOKENS) bracketedEnd(text, valueStart) else unquotedEnd(text, valueStart, key)
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

    /** End of a `{…}`/`[…]` payload, across lines; an unbalanced (truncated) payload runs to the end. */
    private fun bracketedEnd(text: String, start: Int): Int {
        var depth = 0
        for (i in start until text.length) {
            when (text[i]) {
                '{', '[', '(' -> depth++
                '}', ']', ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        return text.length
    }
}
