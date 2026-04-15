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


    /**
     * Generates a minimal tool listing for the system prompt (#341 lazy skill loading).
     *
     * Each non-gateway skill emits only name + one-line description to keep the system prompt
     * compact. [LoadSkillSkill] is always listed first with its call example so the model
     * knows to call it before invoking any other skill. Full parameter docs, examples, and
     * enforcement rules live in each skill's [Skill.fullInstructions] and are retrieved on
     * demand via load_skill.
     */
    fun buildNativeDeclarations(): String {
        if (registry.isEmpty()) return ""
        val strToken = "<|" + "\"" + "|>"
        val loadSkill = registry["load_skill"]
        val others = registry.values
            .filter { it.name != "load_skill" }
            .sortedBy { it.name }

        return buildString {
            appendLine("Available tools — call load_skill first to get full instructions:\n")

            // load_skill always shown with its examples so the model knows how to use it
            if (loadSkill != null) {
                appendLine("• load_skill: ${loadSkill.description}")
                loadSkill.examples.forEach { appendLine("  $it") }
                appendLine()
            }

            // All other skills: name + one-liner only
            appendLine("Other skills (call load_skill to get their instructions):")
            others.forEach { skill ->
                appendLine("• ${skill.name}: ${skill.description}")
            }
        }.trimEnd()
    }

    /** Generates the function_declarations JSON array for the system prompt. */
    fun buildFunctionDeclarationsJson(): String {
        val declarations = org.json.JSONArray()
        registry.values.forEach { skill ->
            val properties = org.json.JSONObject()
            skill.schema.parameters.forEach { (paramName, param) ->
                val paramObj = org.json.JSONObject().apply {
                    put("type", param.type)
                    put("description", param.description)
                    if (!param.enum.isNullOrEmpty()) {
                        put("enum", org.json.JSONArray(param.enum))
                    }
                }
                properties.put(paramName, paramObj)
            }
            val parameters = org.json.JSONObject().apply {
                put("type", "object")
                put("properties", properties)
                put("required", org.json.JSONArray(skill.schema.required))
            }
            val declaration = org.json.JSONObject().apply {
                put("name", skill.name)
                put("description", skill.description)
                put("parameters", parameters)
            }
            declarations.put(declaration)
        }
        return declarations.toString()
    }
}
