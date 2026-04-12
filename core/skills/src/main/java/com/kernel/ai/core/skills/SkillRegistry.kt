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
