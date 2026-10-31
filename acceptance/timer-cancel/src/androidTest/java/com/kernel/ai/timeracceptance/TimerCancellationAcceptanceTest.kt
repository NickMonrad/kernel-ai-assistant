package com.kernel.ai.timeracceptance

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.ContactAliasRepository
import com.kernel.ai.core.memory.ImportantDateRepository
import com.kernel.ai.core.memory.clock.ClockRepository
import com.kernel.ai.core.memory.clock.ClockTimer
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.NoteDao
import com.kernel.ai.core.memory.notification.ListNotificationScheduler
import com.kernel.ai.core.memory.repository.MemoryRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.usecase.NoteSmartTitleUseCase
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.natives.CalendarBirthdayLookup
import com.kernel.ai.core.skills.natives.ClockAlertController
import com.kernel.ai.core.skills.natives.CookingConversionService
import com.kernel.ai.core.skills.natives.CurrencyConversionService
import com.kernel.ai.core.skills.natives.NativeIntentHandler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class TimerCancellationAcceptanceTest {
    private lateinit var context: Context
    private lateinit var clockRepository: ClockRepository
    private lateinit var clockAlertController: ClockAlertController
    private lateinit var handler: NativeIntentHandler

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals(TARGET_APPLICATION_ID, context.packageName)
        assertTrue(context.applicationContext is TimerAcceptanceApplication)

        clockRepository = mockk(relaxed = true)
        clockAlertController = mockk(relaxed = true)
        handler = NativeIntentHandler(
            context = context,
            clockRepository = clockRepository,
            clockAlertController = clockAlertController,
            listItemDao = mockk<ListItemDao>(relaxed = true),
            listNameDao = mockk<ListNameDao>(relaxed = true),
            contactAliasRepository = mockk<ContactAliasRepository>(relaxed = true),
            importantDateRepository = mockk<ImportantDateRepository>(relaxed = true),
            calendarBirthdayLookup = mockk<CalendarBirthdayLookup>(relaxed = true),
            memoryRepository = mockk<MemoryRepository>(relaxed = true),
            embeddingEngine = mockk<EmbeddingEngine>(relaxed = true),
            cookingConversionService = mockk<CookingConversionService>(relaxed = true),
            currencyConversionService = mockk<CurrencyConversionService>(relaxed = true),
            userProfileRepository = mockk<UserProfileRepository>(relaxed = true),
            noteDao = mockk<NoteDao>(relaxed = true),
            noteSmartTitleUseCase = mockk<NoteSmartTitleUseCase>(relaxed = true),
            listNotificationScheduler = mockk<ListNotificationScheduler>(relaxed = true),
        )
    }

    @Test
    fun isolatedPackageHasUniqueStartupAuthorityAndNoSensitivePermissions() {
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS,
        )
        val authorities = packageInfo.providers.orEmpty()
            .flatMap { it.authority?.split(';').orEmpty() }
            .toSet()
        assertTrue("$TARGET_APPLICATION_ID.androidx-startup" in authorities)
        assertFalse("com.kernel.ai.debug.androidx-startup" in authorities)

        val forbiddenPermissions = setOf(
            "android.permission.READ_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.RECEIVE_MMS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.SCHEDULE_EXACT_ALARM",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.CALL_PHONE",
            "android.permission.USE_FULL_SCREEN_INTENT",
            "android.permission.ACCESS_NOTIFICATION_POLICY",
            "android.permission.WRITE_SETTINGS",
            "android.permission.PACKAGE_USAGE_STATS",
        )
        assertTrue(
            forbiddenPermissions.intersect(packageInfo.requestedPermissions.orEmpty().toSet()).isEmpty(),
        )
    }

    @Test
    fun applicationDoesNotInitializeWorkManager() {
        assertThrows(IllegalStateException::class.java) {
            WorkManager.getInstance()
        }
    }

    @Test
    fun singularCancellationWithMultipleTimersClarifiesWithoutCancellingAny() = runBlocking {
        coEvery { clockRepository.getAllTimers() } returns listOf(
            activeTimer("tea", "Tea", durationMs = 60_000L),
            activeTimer("laundry", "Laundry", durationMs = 120_000L),
        )

        val result = handler.handle("cancel_timer", emptyMap())

        assertTrue(result is SkillResult.DirectReply)
        assertTrue((result as SkillResult.DirectReply).content.contains("Which timer should I cancel?"))
        coVerify(exactly = 0) { clockRepository.cancelTimer(any()) }
        coVerify(exactly = 0) { clockRepository.cancelAllTimers() }
    }

    @Test
    fun bothCommandWithTwoTimersCancelsExactlyBoth() = runBlocking {
        coEvery { clockRepository.getAllTimers() } returns listOf(
            activeTimer("tea", "Tea", durationMs = 60_000L),
            activeTimer("laundry", "Laundry", durationMs = 120_000L),
        )
        coEvery { clockRepository.cancelAllTimers() } returns 2

        val route = QuickIntentRouter().route("cancel both timers")
        assertTrue(route is QuickIntentRouter.RouteResult.RegexMatch)
        val intent = (route as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("2", intent.params["count"])
        assertEquals(null, intent.params["all"])

        val result = handler.handle(intent.intentName, intent.params)

        assertEquals(SkillResult.Success("Cancelled 2 timers."), result)
        coVerify(exactly = 1) { clockRepository.cancelAllTimers() }
        coVerify(exactly = 0) { clockRepository.cancelTimer(any()) }
    }

    @Test
    fun bothCommandWithThreeTimersClarifiesWithoutCancellingAny() = runBlocking {
        coEvery { clockRepository.getAllTimers() } returns listOf(
            activeTimer("tea", "Tea", durationMs = 60_000L),
            activeTimer("laundry", "Laundry", durationMs = 120_000L),
            activeTimer("pasta", "Pasta", durationMs = 180_000L),
        )

        val route = QuickIntentRouter().route("cancel both timers")
        assertTrue(route is QuickIntentRouter.RouteResult.RegexMatch)
        val intent = (route as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("2", intent.params["count"])
        assertEquals(null, intent.params["all"])

        val result = handler.handle(intent.intentName, intent.params)

        assertTrue(result is SkillResult.DirectReply)
        val prompt = (result as SkillResult.DirectReply).content
        assertTrue(prompt.contains("3 active timers"))
        assertTrue(prompt.contains("complete commands"))
        coVerify(exactly = 0) { clockRepository.cancelTimer(any()) }
        coVerify(exactly = 0) { clockRepository.cancelAllTimers() }
    }

    @Test
    fun namedCancellationCancelsOnlyTheMatchingTimer() = runBlocking {
        coEvery { clockRepository.getAllTimers() } returns listOf(
            activeTimer("tea", "Tea", durationMs = 60_000L),
            activeTimer("laundry", "Laundry", durationMs = 120_000L),
        )

        val result = handler.handle("cancel_timer_named", mapOf("name" to "Tea"))

        assertEquals(SkillResult.Success("Cancelled the Tea timer"), result)
        coVerify(exactly = 1) { clockRepository.cancelTimer("tea") }
        coVerify(exactly = 0) { clockRepository.cancelAllTimers() }
    }

    @Test
    fun completeOrdinalCommandRoutesThroughQuickIntentRouterAndCancelsOnlyThatTimer() = runBlocking {
        coEvery { clockRepository.getAllTimers() } returns listOf(
            activeTimer("tea", "Tea", 60_000L),
            activeTimer("laundry", "Laundry", 120_000L),
            activeTimer("pasta", "Pasta", 180_000L),
        )

        val route = QuickIntentRouter().route("cancel the second timer")
        assertTrue(route is QuickIntentRouter.RouteResult.RegexMatch)
        val intent = (route as QuickIntentRouter.RouteResult.RegexMatch).intent
        assertEquals("cancel_timer_named", intent.intentName)
        assertEquals("second", intent.params["name"])

        val result = handler.handle(intent.intentName, intent.params)

        assertEquals(SkillResult.Success("Cancelled the second timer"), result)
        coVerify(exactly = 1) { clockRepository.cancelTimer("laundry") }
        coVerify(exactly = 0) { clockRepository.cancelTimer("tea") }
        coVerify(exactly = 0) { clockRepository.cancelTimer("pasta") }
        coVerify(exactly = 0) { clockRepository.cancelAllTimers() }
    }

    private fun activeTimer(id: String, label: String, durationMs: Long): ClockTimer {
        val now = System.currentTimeMillis()
        return ClockTimer(
            id = id,
            triggerAtMillis = now + durationMs,
            label = label,
            createdAtMillis = now,
            durationMs = durationMs,
            startedAtMillis = now,
        )
    }

    private companion object {
        const val TARGET_APPLICATION_ID = "com.kernel.ai.debug.timeracceptance"
    }
}
