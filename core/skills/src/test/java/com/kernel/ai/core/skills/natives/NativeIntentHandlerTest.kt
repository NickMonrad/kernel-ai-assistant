package com.kernel.ai.core.skills.natives

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.ContactsContract
import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.ContactAliasRepository
import com.kernel.ai.core.memory.ImportantDateRepository
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchLap
import com.kernel.ai.core.memory.clock.StopwatchStatus
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ContactAliasEntity
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.skills.SkillResult
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.time.format.DateTimeFormatter
import java.util.Locale

class NativeIntentHandlerTest {
    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val contactAliasRepository = mockk<ContactAliasRepository>(relaxed = true)
    private val importantDateRepository = mockk<ImportantDateRepository>(relaxed = true)
    private val calendarBirthdayLookup = mockk<CalendarBirthdayLookup>(relaxed = true)
    private val clockRepository = mockk<ClockRepository>(relaxed = true)
    private val clockAlertController = mockk<ClockAlertController>(relaxed = true)
    private val listItemDao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)
    private val currencyConversionService = mockk<CurrencyConversionService>(relaxed = true)
    private val handler = NativeIntentHandler(
        context = context,
        clockRepository = clockRepository,
        clockAlertController = clockAlertController,
        listItemDao = listItemDao,
        listNameDao = listNameDao,
        contactAliasRepository = contactAliasRepository,
        importantDateRepository = importantDateRepository,
        calendarBirthdayLookup = calendarBirthdayLookup,
        memoryRepository = mockk<MemoryRepository>(relaxed = true),
        embeddingEngine = mockk<EmbeddingEngine>(relaxed = true),
        currencyConversionService = currencyConversionService,
    )

    private fun handleIntent(intentName: String, params: Map<String, String>): SkillResult =
        runBlocking { handler.handle(intentName, params) }

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(Uri::class)
        mockkStatic(SystemClock::class)
        every { Uri.encode(any()) } answers {
            java.net.URLEncoder.encode(firstArg<String>(), java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20")
        }
        every { Uri.parse(any()) } answers {
            val raw = firstArg<String>()
            mockk<Uri>(relaxed = true).also { uri ->
                every { uri.toString() } returns raw
            }
        }
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { context.contentResolver } returns contentResolver
        every { context.startActivity(any()) } just Runs
        every { SystemClock.elapsedRealtime() } returns 12_000L

        every { clockRepository.observeStopwatch() } returns flowOf(
            ClockStopwatch(
                id = "primary",
                status = StopwatchStatus.IDLE,
                accumulatedElapsedMs = 0L,
                updatedAtMillis = 0L,
            ),
        )

    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class)
        unmockkStatic(Uri::class)
        unmockkStatic(Log::class)
    }

    @Test
    fun `get_list alias normalizes to get_list_items`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "normalizeIntentName",
            String::class.java,
        ).apply { isAccessible = true }

        val normalized = method.invoke(handler, "get_list") as String

        assertEquals("get_list_items", normalized)
    }

    @Test
    fun `resolveTime recovers flattened thirty format`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "36:00") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(6, 30), resolved)
    }

    @Test
    fun `resolveTime preserves valid dotted meridiem times`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "10:00 a.m.") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(10, 0), resolved)
    }

    @Test
    fun `resolveTime recovers split minute voice transcript format`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "8:6:30 p.m.") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(20, 36), resolved)
    }

    @Test
    fun `resolveCalendarSchedule extracts dotted meridiem time from date slot reply`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveCalendarSchedule",
            String::class.java,
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "Sunday at 3:00 p.m.", null) as Pair<*, *>

        assertEquals(LocalDate.now().with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SUNDAY)), resolved.first)
        assertEquals("3:00 p.m.", resolved.second)
    }

    @Test
    fun `resolveTime recovers flattened seven oclock format`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "70:00") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(7, 0), resolved)
    }

    @Test
    fun `resolveTime recovers flattened three oclock format`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "30:00") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(3, 0), resolved)
    }

    @Test
    fun `resolveTime recovers flattened oclock format`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "80:00") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(8, 0), resolved)
    }

    @Test
    fun `get_time resolves world clock city queries`() {
        val result = handleIntent("get_time", mapOf("query_type" to "time", "location" to "London"))

        assertTrue(result is SkillResult.DirectReply)
        assertTrue((result as SkillResult.DirectReply).content.contains("In London"))
    }

    @Test
    fun `get_time reports unknown world clock locations truthfully`() {
        val result = handleIntent("get_time", mapOf("query_type" to "time", "location" to "Middle Earth"))

        assertTrue(result is SkillResult.Failure)
        assertTrue((result as SkillResult.Failure).error.contains("couldn't find a timezone", ignoreCase = true))
    }

    @Test
    fun `make_call resolves direct contact names with punctuation-insensitive matching`() {
        coEvery { contactAliasRepository.getByAlias(any()) } returns null
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                any(),
                any(),
                any(),
                null,
            )
        } returns phoneCursor(
            PhoneRow(
                contactId = "7",
                displayName = "Mary-Jane Watson",
                phoneNumber = "021111222",
            )
        )
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveContactNumber",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "Mary Jane Watson") as String?

        assertEquals("021111222", resolved)
    }

    @Test
    fun `make_call resolves a single contact even when they have multiple numbers`() {
        coEvery { contactAliasRepository.getByAlias(any()) } returns null
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                any(),
                any(),
                any(),
                null,
            )
        } returns phoneCursor(
            PhoneRow(
                contactId = "42",
                displayName = "Alice Smith",
                phoneNumber = "020000000",
                isPrimary = false,
                isSuperPrimary = false,
                phoneType = ContactsContract.CommonDataKinds.Phone.TYPE_HOME,
            ),
            PhoneRow(
                contactId = "42",
                displayName = "Alice Smith",
                phoneNumber = "021999888",
                isPrimary = true,
                isSuperPrimary = true,
                phoneType = ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            ),
        )
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveContactNumber",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "Alice Smith") as String?

        assertEquals("021999888", resolved)
    }

    @Test
    fun `make_call resolves conservative fuzzy surname matches`() {
        coEvery { contactAliasRepository.getByAlias(any()) } returns null
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                any(),
                any(),
                any(),
                null,
            )
        } answers {
            phoneCursor(
                PhoneRow(
                    contactId = "77",
                    displayName = "Michael Sofoclis",
                    phoneNumber = "0271234567",
                ),
            )
        }
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveContactNumber",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "Michael Sophocles") as String?

        assertEquals("0271234567", resolved)
    }

    @Test
    fun `formatSpokenDuration uses full words for timer speech`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "formatSpokenDuration",
            Long::class.javaPrimitiveType,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, 293L) as String

        assertEquals("4 minutes 53 seconds", resolved)
    }

    @Test
    fun `normalizeMediaAppQuery drops generic filler requests`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "normalizeMediaAppQuery",
            String::class.java,
        ).apply { isAccessible = true }

        val generic = method.invoke(handler, "music") as String?
        val meaningful = method.invoke(handler, "Daft Punk") as String?

        assertEquals(null, generic)
        assertEquals("Daft Punk", meaningful)
    }

    @Test
    fun `send email fails fast when contact is missing`() {
        val result = handleIntent("send_email", mapOf(
            "subject" to "Hello",
            "body" to "World",
        ))

        assertEquals(
            SkillResult.Failure("send_email", "No contact specified"),
            result,
        )
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `resolveContactEmail uses alias contact id when available`() {
        coEvery { contactAliasRepository.getByAlias("My wife") } returns
            ContactAliasEntity(
                alias = "wife",
                displayName = "Alice Smith",
                contactId = "42",
                phoneNumber = "021111222",
            )
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                any(),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                match<Array<String>> { it.contentEquals(arrayOf("42")) },
                null,
            )
        } returns emailCursor(
            EmailRow(
                address = "alice@example.com",
                displayName = "Alice Smith",
            ),
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveContactEmail",
            String::class.java,
        ).apply { isAccessible = true }
        val resolved = method.invoke(handler, "My wife") as String?

        assertEquals("alice@example.com", resolved)
    }

    @Test
    fun `resolveContactEmail falls back to alias display name when contact id has no email`() {
        coEvery { contactAliasRepository.getByAlias("My wife") } returns
            ContactAliasEntity(
                alias = "wife",
                displayName = "Alice Smith",
                contactId = "42",
                phoneNumber = "021111222",
            )
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                any(),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                match<Array<String>> { it.contentEquals(arrayOf("42")) },
                null,
            )
        } returns emailCursor()
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                any(),
                any(),
                match<Array<String>> { it.contentEquals(arrayOf("%alice%", "%smith%")) },
                null,
            )
        } returns emailCursor(
            EmailRow(
                address = "alice@example.com",
                displayName = "Alice Smith",
            ),
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveContactEmail",
            String::class.java,
        ).apply { isAccessible = true }
        val resolved = method.invoke(handler, "My wife") as String?

        assertEquals("alice@example.com", resolved)
    }

    @Test
    fun `resolveContactEmail prefers primary email for mapped alias contact`() {
        coEvery { contactAliasRepository.getByAlias("My wife") } returns
            ContactAliasEntity(
                alias = "wife",
                displayName = "Alice Smith",
                contactId = "42",
                phoneNumber = "021111222",
            )
        every {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                any(),
                "${ContactsContract.CommonDataKinds.Email.CONTACT_ID} = ?",
                match<Array<String>> { it.contentEquals(arrayOf("42")) },
                null,
            )
        } returns emailCursor(
            EmailRow(
                address = "alice.work@example.com",
                displayName = "Alice Smith",
                contactId = "42",
            ),
            EmailRow(
                address = "alice.home@example.com",
                displayName = "Alice Smith",
                contactId = "42",
                isPrimary = true,
                isSuperPrimary = true,
            ),
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveContactEmail",
            String::class.java,
        ).apply { isAccessible = true }
        val resolved = method.invoke(handler, "My wife") as String?

        assertEquals("alice.home@example.com", resolved)
    }



    @Test
    fun `add to list fails fast when list name is missing`() {
        val result = handleIntent("add_to_list", mapOf("item" to "milk"))

        assertEquals(
            SkillResult.Failure("add_to_list", "No list name specified"),
            result,
        )
        coVerify(exactly = 0) { listNameDao.insert(any()) }
        coVerify(exactly = 0) { listItemDao.insert(any()) }
    }

    @Test
    fun `shopping list aliases resolve to the same stored list`() {
        coEvery { listItemDao.getByList("shopping list") } returnsMany
            listOf(
                emptyList(),
                listOf(ListItemEntity(listName = "shopping list", item = "milk")),
            )

        val addResult = handleIntent("add_to_list", mapOf(
            "item" to "milk",
            "list_name" to "shopping list",
        ))
        val readResult = handleIntent("get_list_items", mapOf("list_name" to "shopping"))

        assertEquals(
            SkillResult.DirectReply(
                "Added \"milk\" to your shopping list.",
                presentation = addResult.let { (it as SkillResult.DirectReply).presentation },
            ),
            addResult,
        )
        assertEquals(
            SkillResult.DirectReply(
                "shopping list (1 item):\n• milk",
                presentation = readResult.let { (it as SkillResult.DirectReply).presentation },
            ),
            readResult,
        )
        coVerify(exactly = 2) { listItemDao.getByList("shopping list") }
    }

    @Test
    fun `create list shares canonical shopping alias with add and get`() {
        coEvery { listItemDao.getByList("shopping list") } returnsMany
            listOf(
                emptyList(),
                listOf(ListItemEntity(listName = "shopping list", item = "milk")),
            )

        val createResult = handleIntent("create_list", mapOf("list_name" to "shopping"))
        handleIntent("add_to_list", mapOf(
            "item" to "milk",
            "list_name" to "shopping list",
        ))
        val readResult = handleIntent("get_list_items", mapOf("list_name" to "shopping"))

        assertEquals(
            SkillResult.DirectReply(
                "Created list \"shopping list\".",
                presentation = createResult.let { (it as SkillResult.DirectReply).presentation },
            ),
            createResult,
        )
        assertEquals(
            SkillResult.DirectReply(
                "shopping list (1 item):\n• milk",
                presentation = readResult.let { (it as SkillResult.DirectReply).presentation },
            ),
            readResult,
        )
        coVerify(atLeast = 1) { listNameDao.insert(match { it.name == "shopping list" }) }
        coVerify(exactly = 2) { listItemDao.getByList("shopping list") }
    }

    @Test
    fun `playYoutubeMusic launches app and sends play key for generic music query`() {
        val packageManager = mockk<PackageManager>(relaxed = true)
        val audioManager = mockk<AudioManager>(relaxed = true)
        val launchIntent = mockk<Intent>(relaxed = true)
        every { context.packageManager } returns packageManager
        every { packageManager.getLaunchIntentForPackage("com.google.android.apps.youtube.music") } returns launchIntent
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audioManager

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "playYoutubeMusic",
            Map::class.java,
        ).apply { isAccessible = true }

        val result = method.invoke(handler, mapOf("query" to "music")) as com.kernel.ai.core.skills.SkillResult

        assertEquals(
            com.kernel.ai.core.skills.SkillResult.Success("Opening YouTube Music and starting playback"),
            result,
        )
        verify(exactly = 2) { audioManager.dispatchMediaKeyEvent(any()) }
    }

    @Test
    fun `set_alarm without explicit date uses internal clock repository`() {
        coEvery { clockRepository.createAlarm(any()) } returns ClockAlarm(
            id = "alarm-1",
            label = "Wake",
            createdAtMillis = 1_699_000_000_000L,
            enabled = true,
            hour = 7,
            minute = 0,
            repeatRule = com.kernel.ai.core.memory.clock.AlarmRepeatRule.OneOff(19_000L),
            timeZoneId = java.time.ZoneId.systemDefault().id,
            triggerAtMillis = 1_700_000_000_000L,
        )

        val result = handleIntent("set_alarm", mapOf("time" to "07:00", "label" to "Wake"))

        assertTrue(result is SkillResult.Success)
        assertTrue((result as SkillResult.Success).content.contains("Alarm set for"))
        coVerify(exactly = 1) { clockRepository.createAlarm(any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `set_alarm returns exact alarm unavailable failure when platform scheduling is off`() {
        coEvery { clockRepository.createAlarm(any()) } returns null
        every { clockRepository.getPlatformState() } returns com.kernel.ai.core.memory.clock.ClockPlatformState(
            canScheduleExactAlarms = false,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )

        val result = handleIntent("set_alarm", mapOf("time" to "07:00", "label" to "Wake"))

        assertEquals(SkillResult.Failure("run_intent", "Exact alarms are unavailable right now."), result)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `set_alarm returns failure when repository rejects alarm despite exact alarm availability`() {
        coEvery { clockRepository.createAlarm(any()) } returns null
        every { clockRepository.getPlatformState() } returns com.kernel.ai.core.memory.clock.ClockPlatformState(
            canScheduleExactAlarms = true,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )

        val result = handleIntent("set_alarm", mapOf("time" to "07:00", "label" to "Wake"))

        assertEquals(SkillResult.Failure("run_intent", "Could not schedule the alarm."), result)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `start_stopwatch starts a new stopwatch when idle`() {
        coEvery { clockRepository.startStopwatch(any(), any()) } returns ClockStopwatch(
            id = "primary",
            status = StopwatchStatus.RUNNING,
            accumulatedElapsedMs = 0L,
            runningSinceElapsedRealtimeMs = 10_000L,
            runningSinceWallClockMs = 20_000L,
            updatedAtMillis = 20_000L,
        )

        val result = handleIntent("start_stopwatch", emptyMap())

        assertEquals(SkillResult.Success("Started the stopwatch."), result)
        coVerify(exactly = 1) { clockRepository.startStopwatch(any(), any()) }
    }

    @Test
    fun `lap_stopwatch records a lap when stopwatch is running`() {
        every { clockRepository.observeStopwatch() } returns flowOf(
            ClockStopwatch(
                id = "primary",
                status = StopwatchStatus.RUNNING,
                accumulatedElapsedMs = 3_000L,
                runningSinceElapsedRealtimeMs = 10_000L,
                runningSinceWallClockMs = 20_000L,
                updatedAtMillis = 20_000L,
            ),
        )
        coEvery { clockRepository.recordStopwatchLap(any(), any()) } returns StopwatchLap(
            id = 1L,
            lapNumber = 1,
            elapsedMs = 7_500L,
            splitMs = 7_500L,
            createdAtMillis = 27_500L,
        )

        val result = handleIntent("lap_stopwatch", emptyMap())

        assertEquals(SkillResult.Success("Recorded lap 1 at 00:07. Split 00:07."), result)
        coVerify(exactly = 1) { clockRepository.recordStopwatchLap(any(), any()) }
    }

    @Test
    fun `lap_stopwatch fails truthfully when stopwatch is not running`() {
        every { clockRepository.observeStopwatch() } returns flowOf(
            ClockStopwatch(
                id = "primary",
                status = StopwatchStatus.PAUSED,
                accumulatedElapsedMs = 7_500L,
                updatedAtMillis = 27_500L,
            ),
        )

        val result = handleIntent("lap_stopwatch", emptyMap())

        assertEquals(SkillResult.Failure("lap_stopwatch", "No running stopwatch to record a lap."), result)
        coVerify(exactly = 0) { clockRepository.recordStopwatchLap(any(), any()) }
    }

    @Test
    fun `get_stopwatch_status reports paused stopwatch with lap count`() {
        every { clockRepository.observeStopwatch() } returns flowOf(
            ClockStopwatch(
                id = "primary",
                status = StopwatchStatus.PAUSED,
                accumulatedElapsedMs = 12_000L,
                updatedAtMillis = 12_000L,
                laps = listOf(
                    StopwatchLap(id = 1L, lapNumber = 1, elapsedMs = 12_000L, splitMs = 12_000L, createdAtMillis = 12_000L),
                ),
            ),
        )

        val result = handleIntent("get_stopwatch_status", emptyMap())

        assertEquals(SkillResult.DirectReply("Stopwatch paused at 00:12 with 1 lap recorded."), result)
    }


    @Test
    fun `set_timer returns generic failure when repository rejects despite exact alarm availability`() {
        coEvery { clockRepository.scheduleTimer(any(), any()) } returns null
        every { clockRepository.getPlatformState() } returns com.kernel.ai.core.memory.clock.ClockPlatformState(
            canScheduleExactAlarms = true,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )

        val result = handleIntent("set_timer", mapOf("duration_seconds" to "60"))

        assertEquals(SkillResult.Failure("run_intent", "Could not schedule the timer."), result)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `cancel_timer dismisses active timer alert when no running timers remain`() {
        coEvery { clockRepository.cancelAllTimers() } returns 0
        every { clockAlertController.dismissActiveTimerAlerts() } returns true

        val result = handleIntent("cancel_timer", emptyMap())

        assertEquals(SkillResult.Success("Dismissed the active timer alert."), result)
        coVerify(exactly = 1) { clockRepository.cancelAllTimers() }
        verify(exactly = 1) { clockAlertController.dismissActiveTimerAlerts() }
    }

    @Test
    fun `cancel_timer reports cancelling timers and dismissing active alert`() {
        coEvery { clockRepository.cancelAllTimers() } returns 1
        every { clockAlertController.dismissActiveTimerAlerts() } returns true

        val result = handleIntent("cancel_timer", emptyMap())

        assertEquals(
            SkillResult.Success("Cancelled 1 timer and dismissed the active timer alert."),
            result,
        )
        coVerify(exactly = 1) { clockRepository.cancelAllTimers() }
        verify(exactly = 1) { clockAlertController.dismissActiveTimerAlerts() }
    }


    @Test
    fun `cancel_timer_named matches unlabeled timer by hyphenated duration`() {
        coEvery { clockRepository.cancelTimersMatching("10-minute", 600_000L) } returns 1
        val result = handleIntent("cancel_timer_named", mapOf("name" to "10-minute"))
        assertEquals(SkillResult.Success("Cancelled the 10-minute timer"), result)
        coVerify(exactly = 1) { clockRepository.cancelTimersMatching("10-minute", 600_000L) }
    }

    @Test
    fun `set_alarm with unparseable explicit day fails instead of shifting dates`() {
        val result = handleIntent("set_alarm", mapOf("time" to "07:00", "day" to "blursday"))

        assertEquals(SkillResult.Failure("run_intent", "Couldn't parse day: blursday"), result)
        coVerify(exactly = 0) { clockRepository.createAlarm(any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `cancel_timer_named uses truthful failure when no duration match exists`() {
        coEvery { clockRepository.cancelTimersMatching("10-minute", 600_000L) } returns 0
        val result = handleIntent("cancel_timer_named", mapOf("name" to "10-minute"))
        assertEquals(SkillResult.Failure("cancel_timer_named", "No timer matching '10-minute' found"), result)
        coVerify(exactly = 1) { clockRepository.cancelTimersMatching("10-minute", 600_000L) }
    }

    @Test
    fun `cancel_alarm without label cancels next app alarm only`() {
        coEvery { clockRepository.cancelNextAlarm() } returns ClockAlarm(
            id = "alarm-1",
            label = "Wake",
            createdAtMillis = 1_699_000_000_000L,
            enabled = true,
            hour = 7,
            minute = 0,
            repeatRule = com.kernel.ai.core.memory.clock.AlarmRepeatRule.OneOff(19_000L),
            timeZoneId = java.time.ZoneId.systemDefault().id,
            triggerAtMillis = 1_700_000_000_000L,
        )

        val result = handleIntent("cancel_alarm", emptyMap())

        assertEquals(
            SkillResult.Success("Cancelled next app alarm: Wake."),
            result,
        )
        coVerify(exactly = 1) { clockRepository.cancelNextAlarm() }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `cancel_alarm with label cancels internal alarm only`() {
        coEvery { clockRepository.cancelAlarmsByLabel("Wake") } returns 1

        val result = handleIntent("cancel_alarm", mapOf("label" to "Wake"))

        assertEquals(
            SkillResult.Success("Cancelled app alarm: Wake."),
            result,
        )
        coVerify(exactly = 1) { clockRepository.cancelAlarmsByLabel("Wake") }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `cancel_alarm with label reports missing internal alarm`() {
        coEvery { clockRepository.cancelAlarmsByLabel("Wake") } returns 0

        val result = handleIntent("cancel_alarm", mapOf("label" to "Wake"))

        assertEquals(
            SkillResult.Failure("cancel_alarm", "No app alarm named Wake found"),
            result,
        )
        coVerify(exactly = 1) { clockRepository.cancelAlarmsByLabel("Wake") }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `isVoicemailTarget normalizes spaced voicemail phrase`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "isVoicemailTarget",
            String::class.java,
        ).apply { isAccessible = true }

        val spaced = method.invoke(handler, "voice mail") as Boolean
        val compact = method.invoke(handler, "voicemail") as Boolean
        val contact = method.invoke(handler, "voice mail at work") as Boolean

        assertEquals(true, spaced)
        assertEquals(true, compact)
        assertEquals(false, contact)
    }

    @Test
    fun `save important date stores parsed recurring date`() {
        every { Log.d(any<String>(), any<String>()) } returns 0
        coEvery { importantDateRepository.save("mum's birthday", 3, 15, null) } just Runs

        val result = handleIntent("save_important_date", mapOf("label" to "mum's birthday", "date" to "15 March"))

        assertTrue(result is SkillResult.DirectReply)
        assertEquals(
            "I'll remember mum's birthday as 15 March.",
            (result as SkillResult.DirectReply).content,
        )
        coVerify(exactly = 1) { importantDateRepository.save("mum's birthday", 3, 15, null) }
    }

    @Test
    fun `save important date accepts ordinal of month phrasing`() {
        every { Log.d(any<String>(), any<String>()) } returns 0
        coEvery { importantDateRepository.save("Emily's birthday", 11, 19, null) } just Runs

        val result = handleIntent("save_important_date", mapOf("label" to "Emily's birthday", "date" to "19th of November"))

        assertTrue(result is SkillResult.DirectReply)
        assertEquals(
            "I'll remember Emily's birthday as 19 November.",
            (result as SkillResult.DirectReply).content,
        )
        coVerify(exactly = 1) { importantDateRepository.save("Emily's birthday", 11, 19, null) }
    }

    @Test
    fun `list important dates returns stored entries`() {
        coEvery { importantDateRepository.getAll() } returns listOf(
            ImportantDateEntity(label = "mum's birthday", normalizedLabel = "mum birthday", month = 3, day = 15),
            ImportantDateEntity(label = "our anniversary", normalizedLabel = "our anniversary", month = 6, day = 22, year = 2018),
        )

        val result = handleIntent("list_important_dates", emptyMap())

        assertTrue(result is SkillResult.DirectReply)
        val reply = (result as SkillResult.DirectReply).content
        assertTrue(reply.contains("Important dates:"))
        assertTrue(reply.contains("mum's birthday — 15 March"))
        assertTrue(reply.contains("our anniversary — 22 June 2018"))
    }

    @Test
    fun `remove important date deletes by label`() {
        coEvery { importantDateRepository.deleteByLabel("mum's birthday") } returns 1

        val result = handleIntent("remove_important_date", mapOf("label" to "mum's birthday"))

        assertTrue(result is SkillResult.DirectReply)
        assertEquals("Removed important date mum's birthday.", (result as SkillResult.DirectReply).content)
        coVerify(exactly = 1) { importantDateRepository.deleteByLabel("mum's birthday") }
    }

    @Test
    fun `parseDateString resolves taught important dates before holiday lookup`() {
        val today = LocalDate.now()
        val expected = LocalDate.of(today.year, 3, 15).let {
            if (!it.isBefore(today)) it else LocalDate.of(today.year + 1, 3, 15)
        }
        coEvery { importantDateRepository.findByLabel("mum's birthday") } returns ImportantDateEntity(
            label = "mum's birthday",
            normalizedLabel = "mum birthday",
            month = 3,
            day = 15,
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "parseDateString",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "mum's birthday") as LocalDate?

        assertEquals(expected, resolved)
    }

    @Test
    fun `parseDateString resolves calendar birthdays when taught date missing`() {
        val today = LocalDate.now()
        val expected = LocalDate.of(today.year, 4, 5).let {
            if (!it.isBefore(today)) it else LocalDate.of(today.year + 1, 4, 5)
        }
        coEvery { importantDateRepository.findByLabel("jane's birthday") } returns null
        every { calendarBirthdayLookup.findBirthday("jane's birthday") } returns CalendarBirthdayLookup.BirthdayEntry(
            label = "Jane Smith",
            normalizedLabel = "jane smith",
            month = 4,
            day = 5,
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "parseDateString",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "jane's birthday") as LocalDate?

        assertEquals(expected, resolved)
    }

    @Test
    fun `parseDateString resolves mapped birthday aliases through contact names`() {
        val today = LocalDate.now()
        val expected = LocalDate.of(today.year, 4, 5).let {
            if (!it.isBefore(today)) it else LocalDate.of(today.year + 1, 4, 5)
        }
        coEvery { importantDateRepository.findByLabel("my wife's birthday") } returns null
        every { calendarBirthdayLookup.findBirthday("my wife's birthday") } returns null
        coEvery { contactAliasRepository.getByAlias("wife") } returns ContactAliasEntity(
            alias = "wife",
            displayName = "Alice Smith",
            contactId = "42",
            phoneNumber = "021111222",
        )
        every { calendarBirthdayLookup.findBirthday("Alice Smith") } returns CalendarBirthdayLookup.BirthdayEntry(
            label = "Alice Smith",
            normalizedLabel = "alice smith",
            month = 4,
            day = 5,
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "parseDateString",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "my wife's birthday") as LocalDate?

        assertEquals(expected, resolved)
        coVerify(exactly = 1) { contactAliasRepository.getByAlias("wife") }
        verify(exactly = 1) { calendarBirthdayLookup.findBirthday("Alice Smith") }
    }

    @Test
    fun `parseDateString prefers taught dates over calendar birthdays`() {
        val today = LocalDate.now()
        val expected = LocalDate.of(today.year, 3, 15).let {
            if (!it.isBefore(today)) it else LocalDate.of(today.year + 1, 3, 15)
        }
        coEvery { importantDateRepository.findByLabel("mum's birthday") } returns ImportantDateEntity(
            label = "mum's birthday",
            normalizedLabel = "mum birthday",
            month = 3,
            day = 15,
        )

        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "parseDateString",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "mum's birthday") as LocalDate?

        assertEquals(expected, resolved)
        verify(exactly = 0) { calendarBirthdayLookup.findBirthday(any()) }
    }

    @Test
    fun `get_date_diff since uses the most recent recurring important date`() {
        val today = LocalDate.now()
        val expected = LocalDate.of(today.year, 3, 1).let {
            if (!it.isAfter(today)) it else LocalDate.of(today.year - 1, 3, 1)
        }
        coEvery { importantDateRepository.findByLabel("my wedding anniversary") } returns ImportantDateEntity(
            label = "my wedding anniversary",
            normalizedLabel = "wedding anniversary",
            month = 3,
            day = 1,
            year = 2018,
        )

        val result = handleIntent("get_date_diff", mapOf("target_date" to "my wedding anniversary", "direction" to "since"))

        assertTrue(result is SkillResult.DirectReply)
        val content = (result as SkillResult.DirectReply).content
        assertTrue(content.contains("ago"), content)
        assertTrue(content.contains(expected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH))), content)
    }

    @Test
    fun `calculate_arithmetic returns deterministic direct reply`() {
        val result = handleIntent("calculate_arithmetic", mapOf("expression" to "18.5% of 240"))

        assertEquals(SkillResult.DirectReply("The result is 44.4."), result)
    }

    @Test
    fun `calculate_arithmetic rounds spoken summary for approximate replies`() {
        val result = handleIntent("calculate_arithmetic", mapOf("expression" to "1 / 3"))

        assertEquals(
            SkillResult.DirectReply(
                "The result is approximately 0.3333333333333333.",
                spokenSummary = "The result is approximately 0.33.",
            ),
            result,
        )
    }

    @Test
    fun `calculate_arithmetic reports malformed expressions cleanly`() {
        val result = handleIntent("calculate_arithmetic", mapOf("expression" to "2 + )"))

        assertEquals(
            SkillResult.Failure("calculate_arithmetic", "Unexpected token ')'"),
            result,
        )
    }

    @Test
    fun `convert_units returns deterministic direct reply`() {
        val result = handleIntent("convert_units", mapOf("value" to "5", "from_unit" to "miles", "to_unit" to "km"))

        assertEquals(
            SkillResult.DirectReply(
                "5 miles is 8.04672 kilometers.",
                spokenSummary = "5 miles is 8.05 kilometers.",
            ),
            result,
        )
    }

    @Test
    fun `convert_units rounds spoken summary for approximate replies`() {
        val result = handleIntent("convert_units", mapOf("value" to "100", "from_unit" to "m", "to_unit" to "yards"))

        assertEquals(
            SkillResult.DirectReply(
                "100 meters is approximately 109.36132983 yards.",
                spokenSummary = "100 meters is approximately 109.36 yards.",
            ),
            result,
        )
    }

    @Test
    fun `convert_units supports kitchen volume replies`() {
        val result = handleIntent("convert_units", mapOf("value" to "2", "from_unit" to "liters", "to_unit" to "cups"))

        assertEquals(
            SkillResult.DirectReply(
                "2 liters is approximately 8.45350568 cups.",
                spokenSummary = "2 liters is approximately 8.45 cups.",
            ),
            result,
        )
    }

    @Test
    fun `convert_units supports temperature replies`() {
        val result = handleIntent("convert_units", mapOf("value" to "32", "from_unit" to "fahrenheit", "to_unit" to "celsius"))

        assertEquals(
            SkillResult.DirectReply("32 degrees Fahrenheit is 0 degrees Celsius."),
            result,
        )
    }

    @Test
    fun `convert_units formats mixed feet and inches replies`() {
        val result = handleIntent("convert_units", mapOf("value" to "189", "from_unit" to "cm", "to_unit" to "inches"))

        assertEquals(
            SkillResult.DirectReply(
                "189 centimeters is approximately 6 feet and 2.40944882 inches (74.40944882 inches).",
                spokenSummary = "189 centimeters is approximately 6 feet and 2.4 inches.",
            ),
            result,
        )
    }

    @Test
    fun `convert_units supports normalized mixed feet and inches input`() {
        val result = handleIntent("convert_units", mapOf("value" to "74", "from_unit" to "inches", "to_unit" to "cm"))

        assertEquals(
            SkillResult.DirectReply(
                "74 inches is 187.96 centimeters.",
                spokenSummary = null,
            ),
            result,
        )
    }

    @Test
    fun `convert_units supports spoken speed aliases normalized from voice`() {
        val result = handleIntent("convert_units", mapOf("value" to "100", "from_unit" to "kilometers per hour", "to_unit" to "metres per second"))

        assertEquals(
            SkillResult.DirectReply(
                "100 kilometers per hour is approximately 27.77777778 meters per second.",
                spokenSummary = "100 kilometers per hour is approximately 27.78 meters per second.",
            ),
            result,
        )
    }

    @Test
    fun `convert_units rounds spoken exact gallon to liter reply`() {
        val result = handleIntent("convert_units", mapOf("value" to "1", "from_unit" to "gallon", "to_unit" to "litres"))

        assertEquals(
            SkillResult.DirectReply(
                "1 gallon is 3.785411784 liters.",
                spokenSummary = "1 gallon is 3.79 liters.",
            ),
            result,
        )
    }

    @Test
    fun `convert_units reports unsupported units cleanly`() {
        val result = handleIntent("convert_units", mapOf("value" to "5", "from_unit" to "miles", "to_unit" to "parsecs"))

        assertEquals(
            SkillResult.Failure("convert_units", "Unsupported unit 'parsecs'"),
            result,
        )
    }


    @Test
    fun `convert_currency returns truthful direct reply with provenance`() {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } returns CurrencyConversionService.Result(
            inputAmount = java.math.BigDecimal("100"),
            fromCurrency = CurrencyConversionService.ResolvedCurrency("AUD", "Australian Dollar"),
            toCurrency = CurrencyConversionService.ResolvedCurrency("NZD", "New Zealand Dollar"),
            outputAmount = java.math.BigDecimal("121.38"),
            rate = java.math.BigDecimal("1.2138"),
            rateDate = LocalDate.of(2026, 5, 8),
            sourceLabel = "ECB reference rate via Frankfurter",
        )

        val result = handleIntent("convert_currency", mapOf("amount" to "100", "from_currency" to "AUD", "to_currency" to "NZD"))

        assertEquals(
            SkillResult.DirectReply(
                "100 AUD converts to approximately 121.38 NZD. 1 AUD = 1.2138 NZD. This uses the latest ECB reference rate via Frankfurter from 2026-05-08. Exchange rates are not real-time and may have moved since then.",
                spokenSummary = "100 AUD converts to approximately 121.38 NZD at the 8 May 2026 ECB reference rate via Frankfurter.",
            ),
            result,
        )
    }

    @Test
    fun `convert_currency reports stale or unavailable rates cleanly`() {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws IllegalArgumentException("Latest available AUD to NZD rate is from 2026-05-01, which is too stale to use.")

        val result = handleIntent("convert_currency", mapOf("amount" to "100", "from_currency" to "AUD", "to_currency" to "NZD"))

        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                "Latest available AUD to NZD rate is from 2026-05-01, which is too stale to use.",
            ),
            result,
        )
    }

    @Test
    fun `convert_currency maps unexpected exceptions to unavailable message`() {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws RuntimeException("socket timeout")

        val result = handleIntent(
            "convert_currency",
            mapOf("amount" to "100", "from_currency" to "AUD", "to_currency" to "NZD"),
        )

        assertEquals(
            SkillResult.Failure(
                "convert_currency",
                "Currency rates are unavailable right now. I can't do a truthful conversion offline.",
            ),
            result,
        )
    }

    @Test
    fun `convert_currency propagates CancellationException`() {
        coEvery {
            currencyConversionService.convert("100", "AUD", "NZD", any())
        } throws CancellationException("job cancelled")

        assertThrows(CancellationException::class.java) {
            runBlocking {
                handler.handle(
                    "convert_currency",
                    mapOf("amount" to "100", "from_currency" to "AUD", "to_currency" to "NZD"),
                )
            }
        }
    }

    @Test
    fun `convert_currency validates required params`() {
        assertEquals(
            SkillResult.Failure("convert_currency", "No currency amount provided"),
            handleIntent("convert_currency", emptyMap()),
        )
        assertEquals(
            SkillResult.Failure("convert_currency", "No source currency provided"),
            handleIntent("convert_currency", mapOf("amount" to "100", "to_currency" to "NZD")),
        )
        assertEquals(
            SkillResult.Failure("convert_currency", "No target currency provided"),
            handleIntent("convert_currency", mapOf("amount" to "100", "from_currency" to "AUD")),
        )
    }

    @Test
    fun `convert_currency short circuits same currency replies`() {
        coEvery {
            currencyConversionService.convert("100", "USD", "USD", any())
        } returns CurrencyConversionService.Result(
            inputAmount = java.math.BigDecimal("100"),
            fromCurrency = CurrencyConversionService.ResolvedCurrency("USD", "United States Dollar"),
            toCurrency = CurrencyConversionService.ResolvedCurrency("USD", "United States Dollar"),
            outputAmount = java.math.BigDecimal("100"),
            rate = java.math.BigDecimal.ONE,
            rateDate = LocalDate.of(2026, 5, 11),
            sourceLabel = "identity conversion",
        )

        val result = handleIntent(
            "convert_currency",
            mapOf("amount" to "100", "from_currency" to "USD", "to_currency" to "USD"),
        )

        assertEquals(
            SkillResult.DirectReply("100 USD is 100 USD."),
            result,
        )
    }

    private data class PhoneRow(
        val contactId: String,
        val displayName: String,
        val phoneNumber: String,
        val isPrimary: Boolean = false,
        val isSuperPrimary: Boolean = false,
        val phoneType: Int = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
    )

    private data class EmailRow(
        val address: String,
        val displayName: String,
        val contactId: String = "",
        val isPrimary: Boolean = false,
        val isSuperPrimary: Boolean = false,
    )

    private fun phoneCursor(vararg rows: PhoneRow): Cursor {
        val cursor = mockk<Cursor>()
        val columns = mapOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER to 0,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME to 1,
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID to 2,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY to 3,
            ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY to 4,
            ContactsContract.CommonDataKinds.Phone.TYPE to 5,
        )
        var index = -1

        every { cursor.moveToNext() } answers {
            index += 1
            index < rows.size
        }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            columns[firstArg<String>()] ?: error("Unknown column ${firstArg<String>()}")
        }
        every { cursor.getString(0) } answers { rows[index].phoneNumber }
        every { cursor.getString(1) } answers { rows[index].displayName }
        every { cursor.getString(2) } answers { rows[index].contactId }
        every { cursor.getInt(3) } answers { if (rows[index].isPrimary) 1 else 0 }
        every { cursor.getInt(4) } answers { if (rows[index].isSuperPrimary) 1 else 0 }
        every { cursor.getInt(5) } answers { rows[index].phoneType }
        every { cursor.close() } just Runs

        return cursor
    }

    private fun emailCursor(vararg rows: EmailRow): Cursor {
        val cursor = mockk<Cursor>()
        val columns = mapOf(
            ContactsContract.CommonDataKinds.Email.ADDRESS to 0,
            ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY to 1,
            ContactsContract.CommonDataKinds.Email.CONTACT_ID to 2,
            ContactsContract.CommonDataKinds.Email.IS_PRIMARY to 3,
            ContactsContract.CommonDataKinds.Email.IS_SUPER_PRIMARY to 4,
        )
        var index = -1

        every { cursor.moveToNext() } answers {
            index += 1
            index < rows.size
        }
        every { cursor.getColumnIndexOrThrow(any()) } answers {
            columns[firstArg<String>()] ?: error("Unknown column ${firstArg<String>()}")
        }

        every { cursor.getString(0) } answers { rows[index].address }
        every { cursor.getString(1) } answers { rows[index].displayName }
        every { cursor.getString(2) } answers { rows[index].contactId }
        every { cursor.getInt(3) } answers { if (rows[index].isPrimary) 1 else 0 }
        every { cursor.getInt(4) } answers { if (rows[index].isSuperPrimary) 1 else 0 }
        every { cursor.close() } just Runs

        return cursor
    }
}