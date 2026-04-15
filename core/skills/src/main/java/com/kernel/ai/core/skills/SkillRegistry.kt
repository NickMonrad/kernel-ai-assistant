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
     * Generates a minimal skill listing for the system prompt (#341 lazy skill loading).
     *
     * Returns only skill names + one-line descriptions. The SDK auto-generates full
     * tool declarations from @Tool annotations on [KernelAIToolSet], so no parameter
     * schemas or format instructions are needed here.
     */
    fun buildNativeDeclarations(): String {
        if (registry.isEmpty()) return ""
        val others = registry.values
            .filter { it.name != "load_skill" }
            .sortedBy { it.name }

        return buildString {
            others.forEach { skill ->
                appendLine("  - ${skill.name}: ${skill.description.take(80)}")
            }
        }.trimEnd()
    }
}
