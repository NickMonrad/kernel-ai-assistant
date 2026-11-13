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
 * - values of known content keys (`input=…`, `params={…}`, `args=…`, `title=…`);
 * - lines whose remainder is content (`llm_tools_assistant_reply: …`, `DirectReply: …`);
 * - tool calls whose arguments are content (`ToolSet: saveMemory(…)`);
 * - multi-line blocks that dump model output (`Profile LLM extraction succeeded:` + YAML);
 * - email addresses and phone numbers anywhere.
 *
 * It is deliberately conservative: when in doubt it redacts more, never less.
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
    )

    /** Keys whose value is content only when it is a JSON/map payload (e.g. `skill={…}`). */
    private val PAYLOAD_ONLY_KEYS = setOf("skill")

    private val KEY = Regex("""(?<![\w.])([A-Za-z_][\w]*)=""")

    /** Start of the next `key=` token, which ends an unquoted value. */
    private val NEXT_KEY = Regex(""",?\s+[A-Za-z_][\w.]*=""")

    /** Markers after which the rest of the line is user or assistant content. */
    private val REST_OF_LINE = listOf(
        Regex("""(llm_tools_assistant_reply: )[^\n]*"""),
        Regex("""(DirectReply: )[^\n]*"""),
        Regex("""(Skipping duplicate initial query after restore: )[^\n]*"""),
        Regex("""(Auto-titled conversation \S+: )[^\n]*"""),
        Regex("""(with prompt: )[^\n]*"""),
        Regex("""(dropped ungrounded sentence: )[^\n]*"""),
        Regex("""(conversation title for \S+ to )[^\n]*"""),
        Regex("""(SlotValidation: blocked \S+ — )[^\n]*"""),
    )

    /** Tool calls whose whole argument list is content. */
    private val TOOLSET_CONTENT_CALL =
        Regex("""(ToolSet: (?:saveMemory|searchMemory|queryWikipedia|getWeather|runJs)\()[^\n]*(\))""")

    /** `runIntent(intent_name, {json})`: keep the intent name, drop the parameters. */
    private val TOOLSET_RUN_INTENT = Regex("""(ToolSet: runIntent\([^,\n]*, )[^\n]*(\))""")

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

    /** Redacts free text: every line of [text] is treated as a log message body. */
    fun redactText(text: String): String = text.lines().joinToString("\n") { redactMessage(it) }

    /** Redacts one (possibly multi-line) log message body. */
    fun redactMessage(message: String): String {
        if (message.isEmpty()) return message
        var result = BLOCK_DUMP.replace(message) { "${it.groupValues[1]}\n$REDACTED_MARKER" }
        result = TOOLSET_RUN_INTENT.replace(result) { "${it.groupValues[1]}$REDACTED_MARKER${it.groupValues[2]}" }
        result = TOOLSET_CONTENT_CALL.replace(result) { "${it.groupValues[1]}$REDACTED_MARKER${it.groupValues[2]}" }
        REST_OF_LINE.forEach { rule ->
            result = rule.replace(result) { "${it.groupValues[1]}$REDACTED_MARKER" }
        }
        result = DOUBLE_QUOTED.replace(result, "\"$REDACTED_MARKER\"")
        result = SINGLE_QUOTED.replace(result, "'$REDACTED_MARKER'")
        result = SMART_DOUBLE_QUOTED.replace(result, "“$REDACTED_MARKER”")
        result = SMART_SINGLE_QUOTED.replace(result, "‘$REDACTED_MARKER’")
        result = redactKeyValues(result)
        result = EMAIL.replace(result, REDACTED_MARKER)
        result = PHONE.replace(result, REDACTED_MARKER)
        return result
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
            val valueEnd = if (isPayload) bracketedEnd(text, valueStart) else unquotedEnd(text, valueStart)
            out.append(text, cursor, valueStart).append(REDACTED_MARKER)
            cursor = valueEnd
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /** End of an unquoted value: the next ` key=` token or the end of the line. */
    private fun unquotedEnd(text: String, start: Int): Int {
        val lineEnd = text.indexOf('\n', start).let { if (it == -1) text.length else it }
        val next = NEXT_KEY.find(text.substring(start, lineEnd))
        return if (next == null) lineEnd else start + next.range.first
    }

    /** End of a `{…}`/`[…]` payload; an unbalanced (truncated) payload runs to the end of the line. */
    private fun bracketedEnd(text: String, start: Int): Int {
        var depth = 0
        var i = start
        while (i < text.length && text[i] != '\n') {
            when (text[i]) {
                '{', '[', '(' -> depth++
                '}', ']', ')' -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
            i++
        }
        return i
    }
}
