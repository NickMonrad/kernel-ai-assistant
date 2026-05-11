package com.kernel.ai.feature.widget

import android.content.Context
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceSpeakRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class VoiceCommandServiceTest {

    private lateinit var quickIntentRouter: QuickIntentRouter
    private lateinit var skillRegistry: SkillRegistry
    private lateinit var voiceOutputController: VoiceOutputController
    private lateinit var quickActionDao: QuickActionDao
    private lateinit var navigator: WidgetNavigator
    private lateinit var context: Context
    private lateinit var handler: VoiceCommandHandler

    @BeforeEach
    fun setup() {
        quickIntentRouter = mockk()
        skillRegistry = mockk()
        voiceOutputController = mockk(relaxed = true)
        quickActionDao = mockk(relaxed = true)
        navigator = mockk(relaxed = true)
        context = mockk(relaxed = true)
        handler = VoiceCommandHandler(quickIntentRouter, skillRegistry, voiceOutputController, quickActionDao, navigator)
    }

    @Test
    fun `RegexMatch routes to in-place execution and speaks result`() = runTest {
        val transcript = "turn on flashlight"
        val matchedIntent = QuickIntentRouter.MatchedIntent("toggle_flashlight", mapOf("state" to "on"))
        every { quickIntentRouter.route(transcript) } returns QuickIntentRouter.RouteResult.RegexMatch(matchedIntent)

        val skill = mockk<Skill>(relaxed = true)
        every { skill.name } returns "toggle_flashlight"
        every { skillRegistry.get("toggle_flashlight") } returns skill
        coEvery { skill.execute(any()) } returns SkillResult.DirectReply("Flashlight on.", spokenSummary = "Flashlight on.")

        handler.handle(transcript, context)

        coVerify { voiceOutputController.speak(VoiceSpeakRequest("Flashlight on.")) }
        coVerify { quickActionDao.insert(any()) }
    }

    @Test
    fun `FallThrough fires navigateToChat with transcript`() = runTest {
        val transcript = "tell me a story"
        every { quickIntentRouter.route(transcript) } returns QuickIntentRouter.RouteResult.FallThrough(transcript)

        handler.handle(transcript, context)

        verify { navigator.navigateToChat(context, transcript) }
    }

    @Test
    fun `NeedsSlot fires navigateToActions with transcript`() = runTest {
        val transcript = "call"
        val matchedIntent = QuickIntentRouter.MatchedIntent("make_call", emptyMap())
        val slotSpec = mockk<com.kernel.ai.core.skills.slot.SlotSpec>(relaxed = true)
        every { quickIntentRouter.route(transcript) } returns QuickIntentRouter.RouteResult.NeedsSlot(matchedIntent, slotSpec)

        handler.handle(transcript, context)

        verify { navigator.navigateToActions(context, transcript) }
    }

    @Test
    fun `ClassifierMatch routes to in-place execution`() = runTest {
        val transcript = "turn off wifi"
        val matchedIntent = QuickIntentRouter.MatchedIntent("toggle_wifi", mapOf("state" to "off"))
        every { quickIntentRouter.route(transcript) } returns QuickIntentRouter.RouteResult.ClassifierMatch(
            matchedIntent, confidence = 0.92f, needsConfirmation = false
        )

        val skill = mockk<Skill>(relaxed = true)
        every { skill.name } returns "toggle_wifi"
        every { skillRegistry.get("toggle_wifi") } returns skill
        coEvery { skill.execute(any()) } returns SkillResult.Success("Wi-Fi disabled.", spokenSummary = "Wi-Fi disabled.")

        handler.handle(transcript, context)

        coVerify { skill.execute(any()) }
        coVerify { voiceOutputController.speak(VoiceSpeakRequest("Wi-Fi disabled.")) }
    }
}
