package com.kernel.ai.feature.chat

import com.kernel.ai.core.skills.mealplan.MealPlannerActivity
import com.kernel.ai.core.skills.mealplan.MealPlannerActivityState
import com.kernel.ai.feature.chat.model.ChatMessage
import com.kernel.ai.feature.chat.model.ChatUiState
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatScreenKeepAwakeTest {
    @Test
    fun `keeps screen awake while initial chat loading is in progress`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = ChatUiState.Loading,
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
    }

    @Test
    fun `keeps screen awake while model is loading inside ready chat state`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(isLoadingModel = true),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
    }

    @Test
    fun `keeps screen awake while response is generating or speaking`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(isGenerating = true),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Speaking("Reply"),
                voiceMode = ChatViewModel.VoiceMode.OneShot,
            ),
        )
    }

    @Test
    fun `keeps screen awake during one shot voice capture states and back and forth mode`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Listening("hello"),
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = ChatViewModel.VoiceMode.OneShot,
            ),
        )
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Preparing,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = ChatViewModel.VoiceMode.OneShot,
            ),
        )
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Processing("hello"),
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = ChatViewModel.VoiceMode.OneShot,
            ),
        )
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = ChatViewModel.VoiceMode.BackAndForth,
            ),
        )
    }

    @Test
    fun `keeps screen awake while planner work is active`() {
        assertTrue(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
                plannerActivity = MealPlannerActivity(
                    title = "Generating recipe 2 of 5",
                    subtitle = "Chicken stir-fry",
                    state = MealPlannerActivityState.WORKING,
                ),
            ),
        )
    }

    @Test
    fun `planner waiting state does not keep screen awake by itself`() {
        assertFalse(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
                plannerActivity = MealPlannerActivity(
                    title = "Meal plan ready",
                    subtitle = "Say 'show current plan' or 'done meal planning'.",
                    state = MealPlannerActivityState.WAITING,
                ),
            ),
        )
    }

    @Test
    fun `shows inline generation indicator for non streaming user initiated generation`() {
        assertTrue(
            shouldShowInlineGenerationIndicator(
                readyState(
                    isGenerating = true,
                    messages = listOf(
                        ChatMessage(id = "user-1", role = ChatMessage.Role.USER, content = "Low lactose, chicken and beef"),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `hides inline generation indicator when assistant already has streaming bubble`() {
        assertFalse(
            shouldShowInlineGenerationIndicator(
                readyState(
                    isGenerating = true,
                    messages = listOf(
                        ChatMessage(id = "user-1", role = ChatMessage.Role.USER, content = "Plan my meals"),
                        ChatMessage(id = "assistant-1", role = ChatMessage.Role.ASSISTANT, content = "", isStreaming = true),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `does not keep screen awake when chat is idle`() {
        assertFalse(
            shouldKeepChatScreenAwake(
                uiState = readyState(),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
        assertFalse(
            shouldKeepChatScreenAwake(
                uiState = ChatUiState.ModelsNotReady(isDownloading = true),
                voiceCaptureState = ChatViewModel.VoiceCaptureState.Idle,
                voicePlaybackState = ChatViewModel.VoicePlaybackState.Idle,
                voiceMode = null,
            ),
        )
    }

    private fun readyState(
        isGenerating: Boolean = false,
        isLoadingModel: Boolean = false,
        messages: List<ChatMessage> = emptyList(),
    ) = ChatUiState.Ready(
        conversationId = "conv-1",
        conversationTitle = null,
        messages = messages,
        isGenerating = isGenerating,
        isSpeakingResponse = false,
        inputText = "",
        error = null,
        isLoadingModel = isLoadingModel,
        showThinkingProcess = true,
    )
}
