package com.kernel.ai.core.skills

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Phase 4 negative tests — inputs that should NOT match any regex intent and must return null,
 * falling through to the LLM (Gemma E4B) for full natural-language handling.
 *
 * Run with: ./gradlew :core:skills:testDebugUnitTest --tests "*.QuickIntentRouterNegativeTest"
 */
class QuickIntentRouterNegativeTest {

    @ParameterizedTest(name = "[{index}] \"{0}\" → null")
    @MethodSource("fallThroughInputs")
    fun `matchQuickIntent returns null for LLM-bound inputs`(input: String) {
        val intent = QuickIntentRouter.matchQuickIntent(input)
        assertNull(intent, "Expected null (fallthrough) but got intent '${intent?.action}' for: \"$input\"")
    }

    companion object {
        @JvmStatic
        fun fallThroughInputs(): Stream<Arguments> = Stream.of(
            // Must NOT match open_app — "new" is not an app-launch verb
            Arguments.of("new conversation"),
            Arguments.of("new chat"),

            // Must NOT match smart_home — TV and media devices are excluded
            Arguments.of("turn on the tv"),

            // General knowledge / chat — no structural intent
            Arguments.of("tell me about jazz music"),
            Arguments.of("what do you think about that"),

            // The direct tool golden must bypass regex routing; device evidence also rejects classifier routes.
            Arguments.of("Use run_intent.get_date_diff to compare 2026-04-08 with 2026-04-11; return the day difference."),
        )
    }
}
