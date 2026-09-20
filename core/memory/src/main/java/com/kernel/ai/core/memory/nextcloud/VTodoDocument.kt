package com.kernel.ai.core.memory.nextcloud

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A deliberately small VTODO document model that retains unknown property lines verbatim. */
class VTodoDocument private constructor(
    private val lines: MutableList<Line>,
) {
    private sealed interface Line {
        data class Raw(val value: String) : Line
        data class Property(val property: VTodoProperty) : Line
    }

    data class VTodoProperty(
        val name: String,
        val parameters: List<String>,
        val value: String,
        val rawLine: String,
        val generated: Boolean = false,
    ) {
        fun hasParameter(name: String, value: String): Boolean = parameters.any {
            it.substringBefore('=').equals(name, ignoreCase = true) &&
                it.substringAfter('=', "").equals(value, ignoreCase = true)
        }
    }

    fun first(name: String): VTodoProperty? = lines.asSequence()
        .filterIsInstance<Line.Property>()
        .map { it.property }
        .firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun all(name: String): List<VTodoProperty> = lines.asSequence()
        .filterIsInstance<Line.Property>()
        .map { it.property }
        .filter { it.name.equals(name, ignoreCase = true) }
        .toList()

    fun decoded(name: String): String? = first(name)?.value?.let(::unescapeText)

    fun replaceSingle(name: String, value: String, escapeText: Boolean = false) {
        val normalized = name.uppercase(Locale.US)
        lines.removeAll { it is Line.Property && it.property.name == normalized }
        val property = VTodoProperty(
            name = normalized,
            parameters = emptyList(),
            value = if (escapeText) escapeText(value) else value,
            rawLine = "",
            generated = true,
        )
        val insertAt = lines.indexOfLast { it is Line.Raw && it.value.equals("END:VTODO", ignoreCase = true) }
            .takeIf { it >= 0 } ?: lines.size
        lines.add(insertAt, Line.Property(property))
    }

    fun remove(name: String) {
        val normalized = name.uppercase(Locale.US)
        lines.removeAll { it is Line.Property && it.property.name == normalized }
    }

    fun replaceParent(parentUid: String?) {
        lines.removeAll {
            val property = (it as? Line.Property)?.property ?: return@removeAll false
            property.name == "RELATED-TO" && property.hasParameter("RELTYPE", "PARENT")
        }
        if (parentUid != null) {
            val insertAt = lines.indexOfLast { it is Line.Raw && it.value.equals("END:VTODO", ignoreCase = true) }
                .takeIf { it >= 0 } ?: lines.size
            lines.add(
                insertAt,
                Line.Property(
                    VTodoProperty(
                        name = "RELATED-TO",
                        parameters = listOf("RELTYPE=PARENT"),
                        value = parentUid,
                        rawLine = "",
                        generated = true,
                    ),
                ),
            )
        }
    }

    fun setUid(uid: String) = replaceSingle("UID", uid)

    fun render(): String = buildString {
        lines.forEach { line ->
            when (line) {
                is Line.Raw -> append(line.value)
                is Line.Property -> {
                    val p = line.property
                    if (!p.generated) append(p.rawLine)
                    else {
                        append(p.name)
                        p.parameters.forEach { append(';').append(it) }
                        append(':').append(p.value)
                    }
                }
            }
            append("\r\n")
        }
    }

    companion object {
        fun parse(raw: String): VTodoDocument {
            val unfolded = mutableListOf<String>()
            raw.replace("\r\n", "\n").replace('\r', '\n').lines().forEach { line ->
                if (line.isEmpty()) return@forEach
                if (line.startsWith(' ') || line.startsWith('\t')) {
                    require(unfolded.isNotEmpty()) { "Folded iCalendar line has no predecessor" }
                    unfolded[unfolded.lastIndex] += line.drop(1)
                } else unfolded += line
            }
            require(unfolded.any { it.equals("BEGIN:VTODO", ignoreCase = true) }) { "Missing VTODO" }
            require(unfolded.any { it.equals("END:VTODO", ignoreCase = true) }) { "Missing VTODO terminator" }
            return VTodoDocument(unfolded.mapTo(mutableListOf()) { line ->
                parseProperty(line)?.let { Line.Property(it) } ?: Line.Raw(line)
            })
        }

        fun new(uid: String, summary: String, checked: Boolean, dueAt: Long?, parentUid: String?, orderKey: String): VTodoDocument {
            val document = parse(
                "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Jandal//Nextcloud Tasks//EN\r\n" +
                    "BEGIN:VTODO\r\nEND:VTODO\r\nEND:VCALENDAR\r\n",
            )
            document.setUid(uid)
            document.replaceSingle("SUMMARY", summary, escapeText = true)
            document.replaceSingle("STATUS", if (checked) "COMPLETED" else "NEEDS-ACTION")
            if (checked) document.replaceSingle("COMPLETED", UTC_FORMATTER.format(Instant.now()))
            if (dueAt != null) document.replaceSingle("DUE", UTC_FORMATTER.format(Instant.ofEpochMilli(dueAt)))
            document.replaceParent(parentUid)
            document.replaceSingle("X-JANDAL-ORDER", orderKey)
            return document
        }

        private fun parseProperty(line: String): VTodoProperty? {
            val colon = line.indexOf(':')
            if (colon <= 0) return null
            val left = line.substring(0, colon)
            val segments = left.split(';')
            val name = segments.first().uppercase(Locale.US)
            if (name == "BEGIN" || name == "END") return null
            return VTodoProperty(name, segments.drop(1), line.substring(colon + 1), line)
        }

        private val UTC_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}

fun escapeText(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\n", "\\n")

fun unescapeText(value: String): String = buildString {
    var escaped = false
    value.forEach { char ->
        if (escaped) {
            append(if (char == 'n' || char == 'N') '\n' else char)
            escaped = false
        } else if (char == '\\') escaped = true else append(char)
    }
    if (escaped) append('\\')
}

fun parseUtcMillis(value: String?): Long? = value?.let {
    runCatching {
        Instant.from(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).parse(it)).toEpochMilli()
    }.getOrNull() ?: runCatching {
        java.time.LocalDate.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd"))
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }.getOrNull()
}
fun formatUtcMillis(value: Long): String =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        .withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(value))
