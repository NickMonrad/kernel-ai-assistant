package com.kernel.ai.core.skills

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds all registered [Skill] implementations.
 * Skills self-register via Hilt multibinding (@IntoSet).
 */
@Singleton
class SkillRegistry @Inject constructor(
    skills: Set<@JvmSuppressWildcards Skill>,
) {
    private val registry: Map<String, Skill> = skills.associateBy { it.name }

    fun get(name: String): Skill? = registry[name]

    fun allSkills(): List<Skill> = registry.values.toList()

    /** Generates the function_declarations JSON array for FunctionGemma's system prompt. */
    fun buildFunctionDeclarationsJson(): String {
        val declarations = registry.values.map { skill ->
            buildString {
                append("""{"name":"${skill.name}","description":"${skill.description}","parameters":{"type":"object","properties":{""")
                skill.schema.parameters.entries.forEachIndexed { i, (paramName, param) ->
                    if (i > 0) append(",")
                    append(""""$paramName":{"type":"${param.type}","description":"${param.description}"""")
                    if (!param.enum.isNullOrEmpty()) {
                        append(""","enum":[${param.enum.joinToString(",") { "\"$it\"" }}]""")
                    }
                    append("}")
                }
                append("""},"required":[${skill.schema.required.joinToString(",") { "\"$it\"" }}]}""")
                append("}")
            }
        }
        return "[${declarations.joinToString(",")}]"
    }
}
