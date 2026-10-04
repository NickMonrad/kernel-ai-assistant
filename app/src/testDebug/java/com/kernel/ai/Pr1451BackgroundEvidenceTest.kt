package com.kernel.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Pr1451BackgroundEvidenceTest {
    @Test
    fun falseHomeAccessibilityEventResultPassesWhenOtherApplicationIsObserved() {
        val events = mutableListOf<String>()
        var snapshotIndex = 0
        val snapshots = listOf(
            snapshot(applicationWindow(TARGET_PACKAGE, focused = true, active = true)),
            snapshot(applicationWindow(LAUNCHER_PACKAGE, focused = true, active = true)),
        )
        val result = awaitPr1451Background(
            targetPackageName = TARGET_PACKAGE,
            applicationWindowType = APPLICATION_WINDOW_TYPE,
            timeoutMs = 100L,
            pollIntervalMs = 10L,
            pressHome = {
                events += "home"
                false
            },
            snapshotWindows = {
                events += "snapshot"
                snapshots[minOf(snapshotIndex++, snapshots.lastIndex)]
            },
            elapsedRealtimeMs = { 0L },
            sleep = { error("Background was confirmed before polling sleep") },
        )

        assertEquals(listOf("snapshot", "home", "snapshot"), events)
        assertFalse(result.homeAccessibilityEventObserved)
        assertEquals(LAUNCHER_PACKAGE, result.backgroundWindow?.packageName)
        assertEquals(snapshots.last(), result.lastSnapshot)
    }

    @Test
    fun persistentForegroundTimesOutWithWindowEvidenceEvenWhenHomeEventWasObserved() {
        var nowMs = 0L
        val foreground = snapshot(applicationWindow(TARGET_PACKAGE, focused = true, active = true))
        val result = awaitPr1451Background(
            targetPackageName = TARGET_PACKAGE,
            applicationWindowType = APPLICATION_WINDOW_TYPE,
            timeoutMs = 20L,
            pollIntervalMs = 5L,
            pressHome = { true },
            snapshotWindows = { foreground },
            elapsedRealtimeMs = { nowMs },
            sleep = { nowMs += it },
        )

        assertTrue(result.homeAccessibilityEventObserved)
        assertNull(result.backgroundWindow)
        assertEquals(20L, result.elapsedMs)
        assertEquals(foreground, result.lastSnapshot)
        val failure = result.failureMessage(TARGET_PACKAGE, 20L)
        assertTrue(failure.contains(TARGET_PACKAGE))
        assertTrue(failure.contains("focused=true"))
        assertTrue(failure.contains("active=true"))
        assertTrue(failure.contains("home_accessibility_event_observed=true"))
    }

    @Test
    fun keyInjectionAndFocusedSystemWindowDoNotCountAsObservedBackground() {
        var nowMs = 0L
        val systemWindow = snapshot(
            Pr1451ForegroundWindowEvidence(
                packageName = "com.android.systemui",
                type = SYSTEM_WINDOW_TYPE,
                focused = true,
                active = true,
            ),
        )
        val result = awaitPr1451Background(
            targetPackageName = TARGET_PACKAGE,
            applicationWindowType = APPLICATION_WINDOW_TYPE,
            timeoutMs = 10L,
            pollIntervalMs = 5L,
            pressHome = { true },
            snapshotWindows = { systemWindow },
            elapsedRealtimeMs = { nowMs },
            sleep = { nowMs += it },
        )

        assertTrue(result.homeAccessibilityEventObserved)
        assertNull(result.backgroundWindow)
        assertEquals(systemWindow, result.lastSnapshot)
        assertTrue(result.failureMessage(TARGET_PACKAGE, 10L).contains("com.android.systemui"))
    }

    private fun applicationWindow(
        packageName: String,
        focused: Boolean,
        active: Boolean,
    ) = Pr1451ForegroundWindowEvidence(
        packageName = packageName,
        type = APPLICATION_WINDOW_TYPE,
        focused = focused,
        active = active,
    )

    private fun snapshot(window: Pr1451ForegroundWindowEvidence) =
        Pr1451ForegroundWindowSnapshot(windows = listOf(window))

    private companion object {
        const val TARGET_PACKAGE = "com.kernel.ai.debug"
        const val LAUNCHER_PACKAGE = "com.android.launcher3"
        const val APPLICATION_WINDOW_TYPE = 1
        const val SYSTEM_WINDOW_TYPE = 3
    }
}
