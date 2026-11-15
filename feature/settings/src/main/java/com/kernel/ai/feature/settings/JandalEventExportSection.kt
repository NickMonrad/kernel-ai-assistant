package com.kernel.ai.feature.settings

/**
 * Renders the in-app Jandal event section of the support export (#1870).
 * Pure: no Android, no I/O. Each line is passed through [scrub] like every other export section.
 */
internal fun renderJandalEventSection(eventLines: List<String>, scrub: (String) -> String): String = buildString {
    appendLine("Recent Jandal events (in-app log, last 24 hours)")
    appendLine("------------------------------------------------")
    if (eventLines.isEmpty()) {
        appendLine("No Jandal events recorded.")
    } else {
        eventLines.forEach { appendLine(scrub(it)) }
    }
}
