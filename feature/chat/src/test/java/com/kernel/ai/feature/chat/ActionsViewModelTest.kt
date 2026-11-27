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
import com.kernel.ai.core.voice.StartListeningCuePlayer
import com.kernel.ai.core.voice.VoiceInputController
import com.kernel.ai.core.voice.VoiceOutputController
import com.kernel.ai.core.voice.VoiceOutputPreferences
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
    private val voiceOutputPreferences: VoiceOutputPreferences = mockk()
    private val startListeningCuePlayer: StartListeningCuePlayer = mockk(relaxed = true)
    private val voiceInputPreferences: com.kernel.ai.core.voice.VoiceInputPreferences = mockk(relaxed = true)
    private val insertedActions = mutableListOf<QuickActionEntity>()
    private val spokenResponsesEnabled = MutableStateFlow(false)

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
        coEvery { voiceInputController.prewarm() } just Runs
        every { voiceOutputController.events } returns emptyFlow()
        every { voiceOutputController.stop() } just Runs
        every { voiceOutputPreferences.spokenResponsesEnabled } returns spokenResponsesEnabled
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
            speechStopCoordinator = com.kernel.ai.core.voice.SpeechStopCoordinator(io.mockk.mockk(relaxed = true)),
            voiceInputController = voiceInputController,
            voiceOutputController = voiceOutputController,
            voiceOutputPreferences = voiceOutputPreferences,
            startListeningCuePlayer = startListeningCuePlayer,
            voiceInputPreferences = voiceInputPreferences,
        )
    }

    @Test
    fun `typed and voice verb-first arithmetic reach native intent execution`() = runTest(dispatcher) {
        viewModel.executeAction("Add twenty-five and fifteen")
        advanceUntilIdle()

        viewModel.executeAction("Divide one hundred by ten", InputMode.Voice)
        advanceUntilIdle()

        assertEquals(2, insertedActions.size)
        assertEquals("calculate_arithmetic", insertedActions[0].skillName)
        assertEquals("calculate_arithmetic", insertedActions[1].skillName)
        assertEquals(
            mapOf("intent_name" to "calculate_arithmetic", "expression" to "25 + 15"),
            CapturingRunIntentSkill.default.calls[0].arguments,
        )
        assertEquals(
            mapOf("intent_name" to "calculate_arithmetic", "expression" to "100 / 10"),
            CapturingRunIntentSkill.default.calls[1].arguments,
        )
    }

    @Test
    fun `missing custom place list hands Quick Actions request to chat`() = runTest(dispatcher) {
        val input = "what do I need from the garden centre"
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run a native intent"
            override val schema = SkillSchema()
            override suspend fun execute(call: SkillCall): SkillResult =
                SkillResult.Failure(
                    "get_list_items",
                    "I couldn't find a list called \"garden centre\".",
                    fallThroughToModel = true,
                )
        }
        every { skillRegistry.get(any()) } answers {
            if (firstArg<String>() == "run_intent") runIntentSkill else null
        }
        val events = mutableListOf<ActionsViewModel.UiEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.events.collect { events += it }
        }

        viewModel.executeAction(input)
        advanceUntilIdle()

        assertEquals("llm_fallthrough", insertedActions.single().skillName)
        assertEquals(input, insertedActions.single().userQuery)
        assertEquals(
            listOf(ActionsViewModel.UiEvent.NavigateToChat(input, speakResponse = false)),
            events,
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
    fun `contact recovery failure keeps the named prompt out of persisted quick action spoken summary`() = runTest(dispatcher) {
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run a native intent"
            override val schema = SkillSchema()
            override suspend fun execute(call: SkillCall): SkillResult = SkillResult.Failure(
                skillName = "run_intent",
                error = "I don't know who Nick is yet. Add them in Settings → People & Contacts.",
                technicalDetail = "Unresolved contact: send_sms",
                actions = listOf(
                    com.kernel.ai.core.skills.SettingsAction(
                        label = "Add Nick",
                        deepLink = com.kernel.ai.core.skills.SettingsDeepLink(
                            destination = com.kernel.ai.core.skills.ContactLookupRecovery.PEOPLE_DESTINATION,
                        ),
                    ),
                ),
            )
        }
        every { skillRegistry.get(any()) } answers {
            if (firstArg<String>() == "run_intent") runIntentSkill else null
        }

        viewModel.executeAction("send a message")
        advanceUntilIdle()
        viewModel.onSlotReply("Nick")
        advanceUntilIdle()
        viewModel.onSlotReply("Meet me at 5")
        advanceUntilIdle()

        assertEquals(1, insertedActions.size)
        assertEquals("send a message", insertedActions.single().userQuery)
        assertEquals(null, insertedActions.single().spokenSummary)
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
    fun `add to list flow preserves list-name reply for exact-first native resolution`() = runTest(dispatcher) {
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
                "list_name" to "on my shopping list",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `explicitly named list add dispatches add_to_list with extracted arguments`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Please add golden journey item to the list named Widget test")
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "intent_name" to "add_to_list",
                "item" to "golden journey item",
                "list_name" to "Widget test",
            ),
            runIntentSkill.calls.single().arguments,
        )
        assertEquals("add_to_list", insertedActions.single().skillName)
        assertEquals(true, insertedActions.single().isSuccess)
    }
    @Test
    fun `list clarification direct reply is retained as the user-facing action result`() = runTest(dispatcher) {
        val clarification = "What would you like to add?"
        val runIntentSkill = object : Skill {
            override val name = "run_intent"
            override val description = "Run a native intent"
            override val schema = SkillSchema()
            override suspend fun execute(call: SkillCall): SkillResult =
                SkillResult.DirectReply(clarification)
        }
        every { skillRegistry.get(any()) } answers {
            if (firstArg<String>() == "run_intent") runIntentSkill else null
        }

        viewModel.executeAction("Add an item to my shopping list", InputMode.Voice)
        advanceUntilIdle()

        assertEquals("add_to_list", insertedActions.single().skillName)
        assertEquals(clarification, insertedActions.single().resultText)
        assertEquals(true, insertedActions.single().isSuccess)
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
    fun `shopping list replies preserve original names for exact-first resolution`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("add milk to my list")
        advanceUntilIdle()
        viewModel.onSlotReply("on my shopping list")
        advanceUntilIdle()

        assertEquals(
            mapOf(
                "intent_name" to "add_to_list",
                "item" to "milk",
                "list_name" to "on my shopping list",
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
    fun `sequential independent slot fills do not leak values`() = runTest(dispatcher) {
        // Complete open_app with app_name=Spotify
        viewModel.executeAction("open an app")
        advanceUntilIdle()
        assertNotNull(viewModel.pendingSlot.value)
        assertEquals("app_name", viewModel.pendingSlot.value?.request?.missingSlot?.name)

        viewModel.onSlotReply("Spotify")
        advanceUntilIdle()
        assertNull(viewModel.pendingSlot.value)
        assertEquals(1, insertedActions.size)
        // Use the default run_intent skill (set up in setUp)
        assertEquals("Spotify", CapturingRunIntentSkill.default.calls[0].arguments["app_name"])

        // Then complete set_timer with duration_seconds
        CapturingRunIntentSkill.default.reset()
        insertedActions.clear()
        viewModel.executeAction("set a timer")
        advanceUntilIdle()
        assertNotNull(viewModel.pendingSlot.value)
        assertEquals("duration_seconds", viewModel.pendingSlot.value?.request?.missingSlot?.name)

        viewModel.onSlotReply("5 minutes")
        advanceUntilIdle()
        assertNull(viewModel.pendingSlot.value)
        assertEquals(1, insertedActions.size)
        // MUST NOT leak app_name from first slot fill
        assertNull(CapturingRunIntentSkill.default.calls[0].arguments["app_name"])
        assertEquals("300", CapturingRunIntentSkill.default.calls[0].arguments["duration_seconds"])
    }

    @Test
    fun `reminder time reply completes an item-first reminder without a day`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        assertEquals("time", viewModel.pendingSlot.value?.request?.missingSlot?.name)

        viewModel.onSlotReply("5 pm")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(
            mapOf(
                "intent_name" to "add_reminder",
                "item" to "buy milk",
                "time" to "17:00",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `reminder item reply with a time keeps the item and completes without a time question`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        assertEquals("item", viewModel.pendingSlot.value?.request?.missingSlot?.name)

        viewModel.onSlotReply("put the bins out at 5 pm")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(
            mapOf(
                "intent_name" to "add_reminder",
                "item" to "put the bins out",
                "time" to "17:00",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `reminder item reply with a count keeps the count in the item and asks for the time`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()

        viewModel.onSlotReply("call 3 people")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("time", pending.missingSlot.name)
        assertEquals(mapOf("item" to "call 3 people"), pending.existingParams)
    }

    @Test
    fun `reminder item reply with a short day word keeps it in the item and asks for the time`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()

        viewModel.onSlotReply("put on sun cream")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("time", pending.missingSlot.name)
        assertEquals(mapOf("item" to "put on sun cream"), pending.existingParams)
    }

    @Test
    fun `bare set a reminder asks for the item without falling through to chat`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()

        assertEquals("item", viewModel.pendingSlot.value?.request?.missingSlot?.name)
        assertEquals(0, insertedActions.size)
        assertEquals(0, runIntentSkill.calls.size)
    }

    @Test
    fun `incomplete reminder starts slot-fill without navigating to chat`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()

        // Slot-fill is active with time missing
        assertEquals("time", viewModel.pendingSlot.value?.request?.missingSlot?.name)
        assertEquals("add_reminder", viewModel.pendingSlot.value?.request?.intentName)
        assertEquals("buy milk", viewModel.pendingSlot.value?.request?.existingParams["item"])
        // No direct execution
        assertEquals(0, runIntentSkill.calls.size)
        // No fallthrough to chat/LLM (no llm_fallthrough entity inserted)
        assertEquals(0, insertedActions.size)
    }

    @Test
    fun `complete time-only reminder executes directly without slot-fill`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk at 5 pm")
        advanceUntilIdle()

        // No pending slot — executed directly
        assertNull(viewModel.pendingSlot.value)
        assertEquals(1, runIntentSkill.calls.size)
    }

    @Test
    fun `combined reminder with next Monday at 5 pm fills all slots`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        assertEquals("time", viewModel.pendingSlot.value?.request?.missingSlot?.name)

        viewModel.onSlotReply("Next Monday at 5 pm")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(
            mapOf(
                "intent_name" to "add_reminder",
                "item" to "buy milk",
                "day" to "next monday",
                "time" to "17:00",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `reminder item reply with p m time keeps the day and completes with the time`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("ring mum tomorrow at 5 p.m.")
        advanceUntilIdle()

        assertNull(viewModel.pendingSlot.value)
        assertEquals(
            mapOf(
                "intent_name" to "add_reminder",
                "item" to "ring mum",
                "day" to "tomorrow",
                "time" to "17:00",
            ),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `reminder item reply that is only a p m time asks for the item again with the time kept`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("at 5 p.m.")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("item", pending.missingSlot.name)
        assertEquals(mapOf("time" to "17:00"), pending.existingParams)
    }

    @Test
    fun `reminder item reply with a bare hour on tomorrow asks morning or evening and keeps the day`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("ring mum tomorrow at 6")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("time_of_day", pending.missingSlot.name)
        assertEquals("ring mum", pending.existingParams["item"])
        assertEquals("tomorrow", pending.existingParams["day"])
        assertEquals("06:00", pending.existingParams["bare_time"])
    }

    @Test
    fun `reminder tonight then a bare hour reply runs with day today`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk tonight")
        advanceUntilIdle()
        viewModel.onSlotReply("5 pm")
        advanceUntilIdle()

        val arguments = runIntentSkill.calls.single().arguments
        assertEquals("today", arguments["day"])
        assertEquals("17:00", arguments["time"])
    }

    @Test
    fun `spoken reminder item keeps its number words when the reply has a day`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("buy one more tomorrow")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("time", pending.missingSlot.name)
        assertEquals("buy one more", pending.existingParams["item"])
        assertEquals("tomorrow", pending.existingParams["day"])
    }

    @Test
    fun `spoken seven o'clock in the evening is 19 00 and not 70 o'clock`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        viewModel.onSlotReply("seven o'clock in the evening")
        advanceUntilIdle()

        assertEquals("19:00", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken half past seven in the evening completes at 19 30`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        viewModel.onSlotReply("half past seven in the evening")
        advanceUntilIdle()

        assertEquals("19:30", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken ten to eight in the evening completes at 19 50`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }

        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        viewModel.onSlotReply("ten to eight in the evening")
        advanceUntilIdle()

        assertEquals("19:50", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `unclear spoken answer to morning or evening is asked again with an apology`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("ring mum tomorrow at 6")
        advanceUntilIdle()
        viewModel.onSlotReply("yes please")
        advanceUntilIdle()

        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("time_of_day", pending.missingSlot.name)
        assertEquals("Sorry, I didn't catch that. Morning or evening?", pending.missingSlot.promptTemplate)
        assertEquals("06:00", pending.existingParams["bare_time"])
    }

    @Test
    fun `morning or evening answer in words runs the reminder at the pm time`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("ring mum tomorrow at 6")
        advanceUntilIdle()
        viewModel.onSlotReply("in the evening please")
        advanceUntilIdle()
        assertEquals(
            mapOf("intent_name" to "add_reminder", "item" to "ring mum", "day" to "tomorrow", "time" to "18:00"),
            runIntentSkill.calls.single().arguments,
        )
    }

    @Test
    fun `morning or evening answer with a clock time runs the reminder at the pm time`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("ring mum tomorrow at 6")
        advanceUntilIdle()
        viewModel.onSlotReply("6 pm")
        advanceUntilIdle()
        assertEquals("18:00", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken hour oh minutes pm reply runs the reminder at the clock minutes`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }
        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        viewModel.onSlotReply("seven oh five pm")
        advanceUntilIdle()
        assertEquals("19:05", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken this evening at 2 asks do you mean 2 in the morning`() = runTest(dispatcher) {
        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        viewModel.onSlotReply("this evening at 2")
        advanceUntilIdle()
        assertEquals(
            "Do you mean 2 in the morning? Yes or no.",
            viewModel.pendingSlot.value?.request?.missingSlot?.promptTemplate,
        )
    }

    @Test
    fun `spoken item before a day word keeps its number word`() = runTest(dispatcher) {
        viewModel.executeAction("Set a reminder")
        advanceUntilIdle()
        viewModel.onSlotReply("buy one tomorrow")
        advanceUntilIdle()
        val pending = requireNotNull(viewModel.pendingSlot.value).request
        assertEquals("buy one", pending.existingParams["item"])
        assertEquals("tomorrow", pending.existingParams["day"])
    }

    @Test
    fun `spoken tens and unit minute answers the time after tomorrow at six`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> runIntentSkill
                else -> null
            }
        }
        viewModel.executeAction("Remind me to buy milk")
        advanceUntilIdle()
        viewModel.onSlotReply("tomorrow at 6")
        advanceUntilIdle()
        viewModel.onSlotReply("seven twenty one pm")
        advanceUntilIdle()
        assertEquals("19:21", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `reminder without a day then this evening at 7 on tomorrow runs without a morning or evening question`() =
        runTest(dispatcher) {
            val runIntentSkill = CapturingRunIntentSkill()
            every { skillRegistry.get(any()) } answers {
                when (firstArg<String>()) {
                    "run_intent" -> runIntentSkill
                    else -> null
                }
            }
            viewModel.executeAction("Remind me to buy milk tomorrow")
            advanceUntilIdle()
            viewModel.onSlotReply("this evening at 7")
            advanceUntilIdle()
            assertEquals(
                mapOf("intent_name" to "add_reminder", "item" to "buy milk", "day" to "tomorrow", "time" to "19:00"),
                runIntentSkill.calls.single().arguments,
            )
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

    @Test
    fun `spoken reminder reply half past seven in the evening runs at 19 30`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("half past seven in the evening")
        advanceUntilIdle()
        assertEquals("19:30", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken reminder reply seven thirty pm runs at 19 30`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("seven thirty pm")
        advanceUntilIdle()
        assertEquals("19:30", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken reminder reply quarter to eight at night runs at 19 45`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("quarter to eight at night")
        advanceUntilIdle()
        assertEquals("19:45", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken reminder reply quarter past seven pm runs at 19 15`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("quarter past seven pm")
        advanceUntilIdle()
        assertEquals("19:15", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken reminder reply five past seven pm runs at 19 05`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("five past seven pm")
        advanceUntilIdle()
        assertEquals("19:05", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken reminder reply seven in the evening runs at 19 00 without a question`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("seven in the evening")
        advanceUntilIdle()
        assertEquals("19:00", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `speech to text 7 colon 30 p m runs at 19 30`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("7:30 p.m.")
        advanceUntilIdle()
        assertEquals("19:30", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `speech to text 7 30 p m runs at 19 30`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("7 30 p.m.")
        advanceUntilIdle()
        assertEquals("19:30", runIntentSkill.calls.single().arguments["time"])
    }

    @Test
    fun `spoken half past 7 asks morning or evening and the answer sets 19 30`() = runTest(dispatcher) {
        val runIntentSkill = CapturingRunIntentSkill()
        stubRunIntent(runIntentSkill)
        viewModel.executeAction("Remind me to buy milk tomorrow", InputMode.Voice)
        advanceUntilIdle()
        viewModel.onSlotReply("half past 7")
        advanceUntilIdle()
        assertEquals("Morning or evening?", viewModel.pendingSlot.value?.request?.promptMessage)
        viewModel.onSlotReply("in the evening")
        advanceUntilIdle()
        assertEquals("19:30", runIntentSkill.calls.single().arguments["time"])
    }

    private fun stubRunIntent(skill: CapturingRunIntentSkill) {
        every { skillRegistry.get(any()) } answers {
            when (firstArg<String>()) {
                "run_intent" -> skill
                else -> null
            }
        }
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
