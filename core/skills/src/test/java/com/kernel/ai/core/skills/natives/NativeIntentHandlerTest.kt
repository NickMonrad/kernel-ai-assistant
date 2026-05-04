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
import com.kernel.ai.core.memory.clock.ClockAlarm
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockStopwatch
import com.kernel.ai.core.memory.clock.StopwatchLap
import com.kernel.ai.core.memory.clock.StopwatchStatus
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.entity.ContactAliasEntity
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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlinx.coroutines.flow.flowOf

class NativeIntentHandlerTest {
    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val contactAliasRepository = mockk<ContactAliasRepository>(relaxed = true)
    private val clockRepository = mockk<ClockRepository>(relaxed = true)
    private val listItemDao = mockk<ListItemDao>(relaxed = true)
    private val listNameDao = mockk<ListNameDao>(relaxed = true)

    private val handler = NativeIntentHandler(
        context = context,
        clockRepository = clockRepository,
        listItemDao = listItemDao,
        listNameDao = listNameDao,
        contactAliasRepository = contactAliasRepository,
        memoryRepository = mockk<MemoryRepository>(relaxed = true),
        embeddingEngine = mockk<EmbeddingEngine>(relaxed = true),
    )

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
        val result = handler.handle("get_time", mapOf("query_type" to "time", "location" to "London"))

        assertTrue(result is SkillResult.DirectReply)
        assertTrue((result as SkillResult.DirectReply).content.contains("In London"))
    }

    @Test
    fun `get_time reports unknown world clock locations truthfully`() {
        val result = handler.handle("get_time", mapOf("query_type" to "time", "location" to "Middle Earth"))

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
        val result = handler.handle(
            "send_email",
            mapOf(
                "subject" to "Hello",
                "body" to "World",
            ),
        )

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
                "${ContactsContract.CommonDataKinds.Email.DISPLAY_NAME_PRIMARY} LIKE ?",
                match<Array<String>> { it.contentEquals(arrayOf("%Alice Smith%")) },
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
    fun `add to list fails fast when list name is missing`() {
        val result = handler.handle(
            "add_to_list",
            mapOf("item" to "milk"),
        )

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

        val addResult = handler.handle(
            "add_to_list",
            mapOf(
                "item" to "milk",
                "list_name" to "shopping list",
            ),
        )
        val readResult = handler.handle(
            "get_list_items",
            mapOf("list_name" to "shopping"),
        )

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

        val createResult = handler.handle(
            "create_list",
            mapOf("list_name" to "shopping"),
        )
        handler.handle(
            "add_to_list",
            mapOf(
                "item" to "milk",
                "list_name" to "shopping list",
            ),
        )
        val readResult = handler.handle(
            "get_list_items",
            mapOf("list_name" to "shopping"),
        )

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

        val result = handler.handle("set_alarm", mapOf("time" to "07:00", "label" to "Wake"))

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

        val result = handler.handle("set_alarm", mapOf("time" to "07:00", "label" to "Wake"))

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

        val result = handler.handle("set_alarm", mapOf("time" to "07:00", "label" to "Wake"))

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

        val result = handler.handle("start_stopwatch", emptyMap())

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

        val result = handler.handle("lap_stopwatch", emptyMap())

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

        val result = handler.handle("lap_stopwatch", emptyMap())

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

        val result = handler.handle("get_stopwatch_status", emptyMap())

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

        val result = handler.handle("set_timer", mapOf("duration_seconds" to "60"))

        assertEquals(SkillResult.Failure("run_intent", "Could not schedule the timer."), result)
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `cancel_timer_named matches unlabeled timer by hyphenated duration`() {
        coEvery { clockRepository.cancelTimersMatching("10-minute", 600_000L) } returns 1
        val result = handler.handle("cancel_timer_named", mapOf("name" to "10-minute"))
        assertEquals(SkillResult.Success("Cancelled the 10-minute timer"), result)
        coVerify(exactly = 1) { clockRepository.cancelTimersMatching("10-minute", 600_000L) }
    }

    @Test
    fun `set_alarm with unparseable explicit day fails instead of shifting dates`() {
        val result = handler.handle("set_alarm", mapOf("time" to "07:00", "day" to "blursday"))

        assertEquals(SkillResult.Failure("run_intent", "Couldn't parse day: blursday"), result)
        coVerify(exactly = 0) { clockRepository.createAlarm(any()) }
        verify(exactly = 0) { context.startActivity(any()) }
    }

    @Test
    fun `cancel_timer_named uses truthful failure when no duration match exists`() {
        coEvery { clockRepository.cancelTimersMatching("10-minute", 600_000L) } returns 0
        val result = handler.handle("cancel_timer_named", mapOf("name" to "10-minute"))
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

        val result = handler.handle("cancel_alarm", emptyMap())

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

        val result = handler.handle("cancel_alarm", mapOf("label" to "Wake"))

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

        val result = handler.handle("cancel_alarm", mapOf("label" to "Wake"))

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
        every { cursor.close() } just Runs

        return cursor
    }
}