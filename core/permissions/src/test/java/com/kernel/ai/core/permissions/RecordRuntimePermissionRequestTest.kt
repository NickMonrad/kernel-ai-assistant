package com.kernel.ai.core.permissions

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class RecordRuntimePermissionRequestTest {

    @Test
    fun `records every permission passed in a single call as requested`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        context.recordRuntimePermissionRequest("android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS")

        verify(exactly = 1) { editor.putBoolean("android.permission.RECORD_AUDIO", true) }
        verify(exactly = 1) { editor.putBoolean("android.permission.READ_CONTACTS", true) }
    }
}
