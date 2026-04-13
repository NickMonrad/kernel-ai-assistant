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
     * Generates a human-readable, native-token-format list of available tools for injection
     * into the Gemma 4 system prompt. Each entry shows the exact <|tool_call> syntax the
     * model must emit — with a concrete usage example using real function names.
     */
    fun buildNativeDeclarations(): String {
        if (registry.isEmpty()) return ""
        val strToken = "<|" + "\"" + "|>"
        val sb = StringBuilder("Available tools — use the EXACT function name shown:\n\n")
        registry.values.sortedBy { it.name }.forEach { skill ->
            sb.append("• ${skill.name}: ${skill.description}\n")
            val exampleArgs = if (skill.schema.parameters.isEmpty()) {
                ""
            } else {
                skill.schema.parameters.entries.mapIndexed { i, (k, v) ->
                    val exVal = when {
                        !v.enum.isNullOrEmpty() -> "$strToken${v.enum!!.first()}$strToken"
                        v.type == "integer" || v.type == "number" -> "0"
                        else -> "${strToken}value$strToken"
                    }
                    "$k:$exVal"
                }.joinToString(",")
            }
            sb.append("  Call: <|tool_call>call:${skill.name}{$exampleArgs}<tool_call|>\n")
            if (skill.schema.parameters.isNotEmpty()) {
                skill.schema.required.forEach { req ->
                    val p = skill.schema.parameters[req]
                    if (p != null) {
                        val enumNote = if (!p.enum.isNullOrEmpty()) " [${p.enum!!.joinToString("/")}]" else ""
                        sb.append("  $req (${p.type}$enumNote): ${p.description}\n")
                    }
                }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

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
