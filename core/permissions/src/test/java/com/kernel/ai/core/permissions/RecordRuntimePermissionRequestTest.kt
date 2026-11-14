package com.kernel.ai.core.permissions

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class RecordPermissionResultTest {

    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val prefs = mockk<SharedPreferences>()
    private val context = mockk<Context>()
    private val permission = "android.permission.READ_CONTACTS"

    init {
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { context.getSharedPreferences(any(), any()) } returns prefs
    }

    @Test
    fun `granted result clears the recorded denial`() {
        every { editor.remove(permission) } returns editor

        context.recordPermissionResult(permission, granted = true, shouldShowRationale = false)

        verify(exactly = 1) { editor.remove(permission) }
    }

    @Test
    fun `denial with rationale still available is recorded`() {
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = true)

        verify(exactly = 1) { editor.putBoolean(permission, true) }
    }

    @Test
    fun `dismissed dialog without rationale is not recorded as a denial`() {
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = false)

        verify(exactly = 0) { editor.putBoolean(permission, true) }
    }
}
