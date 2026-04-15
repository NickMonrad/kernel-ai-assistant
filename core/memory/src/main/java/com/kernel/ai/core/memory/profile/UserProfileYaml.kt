package com.kernel.ai.core.memory.profile

/**
 * Structured representation of the user profile, derived from free-text.
 * Injected into the system prompt as compact YAML for token efficiency.
 */
data class UserProfileYaml(
    val name: String? = null,
    val role: String? = null,
    val location: String? = null,
    val environment: List<String> = emptyList(),
    val context: List<String> = emptyList(),
    val rules: List<String> = emptyList(),
) {
    /** Render as compact YAML block for system prompt injection. */
    fun toYaml(): String = buildString {
        name?.let { appendLine("name: $it") }
        role?.let { appendLine("role: $it") }
        location?.let { appendLine("location: $it") }
        if (environment.isNotEmpty()) {
            appendLine("environment:")
            environment.forEach { appendLine("  - $it") }
        }
        if (context.isNotEmpty()) {
            appendLine("context:")
            context.forEach { appendLine("  - $it") }
        }
        if (rules.isNotEmpty()) {
            appendLine("rules:")
            rules.forEach { appendLine("  - $it") }
        }
    }.trimEnd()

    fun isEmpty(): Boolean =
        name == null && role == null && location == null && environment.isEmpty() && context.isEmpty() && rules.isEmpty()

    fun toJson(): String = buildString {
        append("{")
        val parts = mutableListOf<String>()
        name?.let { parts.add("\"name\":\"${it.escapeJson()}\"") }
        role?.let { parts.add("\"role\":\"${it.escapeJson()}\"") }
        location?.let { parts.add("\"location\":\"${it.escapeJson()}\"") }
        if (environment.isNotEmpty()) parts.add("\"environment\":[${environment.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        if (context.isNotEmpty()) parts.add("\"context\":[${context.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        if (rules.isNotEmpty()) parts.add("\"rules\":[${rules.joinToString(",") { "\"${it.escapeJson()}\"" }}]")
        append(parts.joinToString(","))
        append("}")
    }

    companion object {
        fun fromJson(text: String): UserProfileYaml? = runCatching {
            val obj = org.json.JSONObject(text)
            UserProfileYaml(
                name = obj.optString("name").ifEmpty { null },
                role = obj.optString("role").ifEmpty { null },
                location = obj.optString("location").ifEmpty { null },
                environment = obj.optJSONArray("environment")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                context = obj.optJSONArray("context")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                rules = obj.optJSONArray("rules")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
            )
        }.getOrNull()

        private fun String.escapeJson(): String =
            replace("\\\\", "\\\\\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    }
}
