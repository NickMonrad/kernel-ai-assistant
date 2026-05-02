package com.kernel.ai.feature.chat

import android.util.Log
import com.kernel.ai.core.memory.dao.QuickActionDao
import com.kernel.ai.core.memory.entity.QuickActionEntity
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActionsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val quickIntentRouter = QuickIntentRouter()
    private val skillRegistry: SkillRegistry = mockk()
    private val quickActionDao: QuickActionDao = mockk()
    private val voiceInputController: VoiceInputController = mockk()
    private val voiceOutputController: VoiceOutputController = mockk()
    private val insertedActions = mutableListOf<QuickActionEntity>()

    private lateinit var viewModel: ActionsViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { quickActionDao.observeAll() } returns flowOf(emptyList())
        coEvery { quickActionDao.insert(capture(insertedActions)) } just Runs
        every { voiceInputController.events } returns emptyFlow()
        every { voiceInputController.stopListening() } just Runs
        every { voiceOutputController.events } returns emptyFlow()
        every { voiceOutputController.stop() } just Runs
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> CapturingRunIntentSkill.default
                else -> null
            }
        }
        viewModel = ActionsViewModel(
            quickIntentRouter = quickIntentRouter,
            skillRegistry = skillRegistry,
            quickActionDao = quickActionDao,
            voiceInputController = voiceInputController,
            voiceOutputController = voiceOutputController,
        )
    }

    @AfterEach
    fun tearDown() {
        insertedActions.clear()
        CapturingRunIntentSkill.default.reset()
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `send sms bare flow asks for contact then message before executing`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("send a message")
        advanceUntilIdle()

        assertEquals("Who do you want to send a message to?", viewModel.pendingSlot.value?.request?.promptMessage)
        assertEquals(0, insertedActions.size)
        assertEquals(0, runIntentSkill.calls.size)

        viewModel.onSlotReply("Nick")
        advanceUntilIdle()

        val pendingMessage = viewModel.pendingSlot.value
        assertNotNull(pendingMessage)
        assertEquals("message", pendingMessage?.request?.missingSlot?.name)
        assertEquals("What would you like to say to Nick?", pendingMessage?.request?.promptMessage)
        assertEquals(0, insertedActions.size)
        assertEquals(0, runIntentSkill.calls.size)

        viewModel.onSlotReply("Meet me at 5")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(1, insertedActions.size)
        assertEquals(1, runIntentSkill.calls.size)
        assertEquals(
            mapOf(
                "intent_name" to "send_sms",
                "contact" to "Nick",
                "message" to "Meet me at 5",
            ),
            runIntentSkill.calls.single().arguments,
        )
        assertEquals("send a message", insertedActions.single().userQuery)
        assertEquals("send_sms", insertedActions.single().skillName)
        assertEquals("Executed send_sms", insertedActions.single().resultText)
        assertEquals(true, insertedActions.single().isSuccess)
    }

    @Test
    fun `send email bare flow waits for contact subject and body before executing`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("send an email")
        advanceUntilIdle()

        assertEquals("Who would you like to email?", viewModel.pendingSlot.value?.request?.promptMessage)
        assertEquals(0, insertedActions.size)

        viewModel.onSlotReply("to Nick")
        advanceUntilIdle()

        assertEquals("subject", viewModel.pendingSlot.value?.request?.missingSlot?.name)
        assertEquals(
            "What's the subject of your email to Nick?",
            viewModel.pendingSlot.value?.request?.promptMessage,
        )
        assertEquals(0, insertedActions.size)
        assertEquals(0, runIntentSkill.calls.size)

        viewModel.onSlotReply("Weekend plans")
        advanceUntilIdle()

        assertEquals("body", viewModel.pendingSlot.value?.request?.missingSlot?.name)
        assertEquals("What would you like the email to say?", viewModel.pendingSlot.value?.request?.promptMessage)
        assertEquals(0, insertedActions.size)
        assertEquals(0, runIntentSkill.calls.size)

        viewModel.onSlotReply("Let's catch up on Saturday")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(1, insertedActions.size)
        assertEquals(1, runIntentSkill.calls.size)
        assertEquals(
            mapOf(
                "intent_name" to "send_email",
                "contact" to "Nick",
                "subject" to "Weekend plans",
                "body" to "Let's catch up on Saturday",
            ),
            runIntentSkill.calls.single().arguments,
        )
        assertEquals("send an email", insertedActions.single().userQuery)
        assertEquals("send_email", insertedActions.single().skillName)
        assertEquals("Executed send_email", insertedActions.single().resultText)
    }

    @Test
    fun `add to list flow normalizes natural list-name replies before executing`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("add to my list")
        advanceUntilIdle()

        assertEquals("What would you like to add?", viewModel.pendingSlot.value?.request?.promptMessage)

        viewModel.onSlotReply("milk")
        advanceUntilIdle()

        assertEquals("list_name", viewModel.pendingSlot.value?.request?.missingSlot?.name)
        assertEquals("Which list should I add it to?", viewModel.pendingSlot.value?.request?.promptMessage)

        viewModel.onSlotReply("on my shopping list")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(
            mapOf(
                "intent_name" to "add_to_list",
                "item" to "milk",
                "list_name" to "shopping list",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `create list flow preserves leading words in list names`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("create a list")
        advanceUntilIdle()

        assertEquals("What would you like to call the list?", viewModel.pendingSlot.value?.request?.promptMessage)

        viewModel.onSlotReply("The Boys")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(
            mapOf(
                "intent_name" to "create_list",
                "list_name" to "The Boys",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `generic shopping list replies normalize without mangling named lists`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("add milk to my list")
        advanceUntilIdle()
        viewModel.onSlotReply("my shopping list")
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "intent_name" to "add_to_list",
                "item" to "milk",
                "list_name" to "shopping list",
            ),
            runIntentSkill.calls.single().arguments,
        )

        runIntentSkill.calls.clear()
        viewModel.executeAction("create a list")
        advanceUntilIdle()
        viewModel.onSlotReply("My Tasks")
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "intent_name" to "create_list",
                "list_name" to "My Tasks",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `blank slot reply cancels slot fill and inserts nothing`() = runTest(dispatcher) {
        viewModel.executeAction("send a message")
        advanceUntilIdle()

        assertNotNull(viewModel.pendingSlot.value)

        viewModel.onSlotReply("   ")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(0, insertedActions.size)
    }

    @Test
    fun `executeAction is ignored while slot fill is pending`() = runTest(dispatcher) {
        viewModel.executeAction("send a message")
        advanceUntilIdle()

        val pendingBefore = viewModel.pendingSlot.value
        assertNotNull(pendingBefore)

        viewModel.executeAction("send an email")
        advanceUntilIdle()

        val pendingAfter = viewModel.pendingSlot.value
        assertNotNull(pendingAfter)
        assertEquals(pendingBefore?.request?.intentName, pendingAfter?.request?.intentName)
        assertEquals(pendingBefore?.request?.missingSlot?.name, pendingAfter?.request?.missingSlot?.name)
        assertEquals(pendingBefore?.request?.promptMessage, pendingAfter?.request?.promptMessage)
        assertEquals(0, insertedActions.size)
    }

    @Test
    fun `onSlotReply with no pending slot is a no op`() = runTest(dispatcher) {
        viewModel.onSlotReply("Nick")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(0, insertedActions.size)
    }

    @Test
    fun `slot reply execution failure records truthful error state`() = runTest(dispatcher) {
        val failingSkill = CapturingRunIntentSkill { throw IllegalStateException("Dialer exploded") }
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> failingSkill
                else -> null
            }
        }

        viewModel.executeAction("send a message")
        advanceUntilIdle()
        viewModel.onSlotReply("Nick")
        advanceUntilIdle()
        viewModel.onSlotReply("Meet me at 5")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals("Dialer exploded", viewModel.error.value)
        assertEquals(1, insertedActions.size)
        assertEquals("Error: Dialer exploded", insertedActions.single().resultText)
        assertEquals(false, insertedActions.single().isSuccess)
    }

    private class CapturingRunIntentSkill(
        private val responder: (SkillCall) -> SkillResult = {
            SkillResult.Success("Executed ${it.arguments["intent_name"]}")
        },
    ) : Skill {
        val calls = mutableListOf<SkillCall>()

        override val name: String = "run_intent"
        override val description: String = "Run intent"
        override val schema: SkillSchema = SkillSchema()

        override suspend fun execute(call: SkillCall): SkillResult {
            calls += call
            return responder(call)
        }

        fun reset() {
            calls.clear()
        }

        companion object {
            val default = CapturingRunIntentSkill()
        }
    }
}
