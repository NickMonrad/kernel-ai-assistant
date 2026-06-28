package com.kernel.ai.core.permissions

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class VoicePermissionPromptFactoryTest {

    @Test
    fun `create returns non-null config for CHAT_VOICE Missing state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.Missing,
        )
        assertNotNull(config)
        assertEquals(
            VoicePermissionPromptState.Missing,
            config.state,
        )
        assertEquals(
            R.string.voice_permission_chat_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_chat_description,
            config.descriptionRes,
        )
        assertEquals(
            R.string.voice_permission_grant,
            config.positiveButtonRes,
        )
        assertEquals(
            R.string.voice_permission_cancel,
            config.negativeButtonRes,
        )
    }

    @Test
    fun `create returns non-null config for CHAT_VOICE Denied state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.Denied,
        )
        assertNotNull(config)
        assertEquals(
            VoicePermissionPromptState.Denied,
            config.state,
        )
        assertEquals(
            R.string.voice_permission_denied_chat_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_denied_chat_description,
            config.descriptionRes,
        )
        assertEquals(
            R.string.voice_permission_retry,
            config.positiveButtonRes,
        )
        assertEquals(
            R.string.voice_permission_cancel,
            config.negativeButtonRes,
        )
    }

    @Test
    fun `create returns non-null config for CHAT_VOICE PermanentlyDenied state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.PermanentlyDenied,
        )
        assertNotNull(config)
        assertEquals(
            VoicePermissionPromptState.PermanentlyDenied,
            config.state,
        )
        assertEquals(
            R.string.voice_permission_permanently_denied_chat_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_permanently_denied_chat_description,
            config.descriptionRes,
        )
        assertEquals(
            R.string.voice_permission_open_settings,
            config.positiveButtonRes,
        )
        assertEquals(
            R.string.voice_permission_cancel,
            config.negativeButtonRes,
        )
    }

    @Test
    fun `create returns non-null config for ACTIONS_VOICE Missing state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.ACTIONS_VOICE,
            VoicePermissionPromptState.Missing,
        )
        assertNotNull(config)
        assertEquals(
            R.string.voice_permission_actions_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_actions_description,
            config.descriptionRes,
        )
    }

    @Test
    fun `create returns non-null config for WIDGET_VOICE Missing state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.WIDGET_VOICE,
            VoicePermissionPromptState.Missing,
        )
        assertNotNull(config)
        assertEquals(
            R.string.voice_permission_widget_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_widget_description,
            config.descriptionRes,
        )
    }

    @Test
    fun `create returns non-null config for VOICE_SETTINGS Missing state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.VOICE_SETTINGS,
            VoicePermissionPromptState.Missing,
        )
        assertNotNull(config)
        assertEquals(
            R.string.voice_permission_settings_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_settings_description,
            config.descriptionRes,
        )
    }

    @Test
    fun `create returns non-null config for VOICE_SETTINGS PermanentlyDenied state`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.VOICE_SETTINGS,
            VoicePermissionPromptState.PermanentlyDenied,
        )
        assertNotNull(config)
        assertEquals(
            R.string.voice_permission_permanently_denied_settings_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_permanently_denied_settings_description,
            config.descriptionRes,
        )
    }

    @Test
    fun `create returns distinct configs for different entry points`() {
        val chatConfig = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.Missing,
        )
        val actionsConfig = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.ACTIONS_VOICE,
            VoicePermissionPromptState.Missing,
        )
        // Different entry points should have different title resource IDs
        assertEquals(false, chatConfig.titleRes == actionsConfig.titleRes)
    }

    @Test
    fun `create returns distinct configs for different states`() {
        val missingConfig = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.Missing,
        )
        val deniedConfig = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.Denied,
        )
        // Different states should have different title resource IDs
        assertEquals(false, missingConfig.titleRes == deniedConfig.titleRes)
    }

    @Test
    fun `create returns Granted config with ok button`() {
        val config = VoicePermissionPromptFactory.create(
            VoicePermissionEntryPoint.CHAT_VOICE,
            VoicePermissionPromptState.Granted,
        )
        assertEquals(
            VoicePermissionPromptState.Granted,
            config.state,
        )
        assertEquals(
            R.string.voice_permission_granted_title,
            config.titleRes,
        )
        assertEquals(
            R.string.voice_permission_granted_description,
            config.descriptionRes,
        )
        assertEquals(
            R.string.voice_permission_ok,
            config.positiveButtonRes,
        )
        assertEquals(null, config.negativeButtonRes)
    }
}
