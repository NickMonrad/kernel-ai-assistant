package com.kernel.ai.feature.chat

/**
 * Silence re-prompts per voice turn, shared by Actions and chat so the surfaces stay in step (#2070).
 * A pending slot question gets two re-asks ("Sorry, I didn't catch that. <slot question>"); plain command
 * silence gets one. After the budget is spent the error surfaces and the mic stays closed.
 */
internal const val SLOT_REPLY_MAX_VOICE_RETRIES = 2
internal const val COMMAND_MAX_VOICE_RETRIES = 1
