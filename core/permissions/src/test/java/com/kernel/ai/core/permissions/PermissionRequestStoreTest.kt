package com.kernel.ai.core.permissions

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SharedPreferences backed by [values], so store reads see writes: a real round trip. */
private fun mapPrefs(values: MutableMap<String, Any>): SharedPreferences {
    val editor = mockk<SharedPreferences.Editor>()
    every { editor.putBoolean(any(), any()) } answers {
        values[firstArg<String>()] = secondArg<Boolean>()
        editor
    }
    every { editor.remove(any()) } answers {
        values.remove(firstArg<String>())
        editor
    }
    every { editor.apply() } returns Unit
    val prefs = mockk<SharedPreferences>()
    every { prefs.edit() } returns editor
    every { prefs.getBoolean(any(), any()) } answers {
        (values[firstArg<String>()] as? Boolean) ?: secondArg<Boolean>()
    }
    return prefs
}

class PermissionRequestStoreTest {

    private val permission = "android.permission.READ_CONTACTS"
    private val values = mutableMapOf<String, Any>()
    private val store = PermissionRequestStore(mapPrefs(values))
    private val context = mockk<Context>()

    init {
        every { context.getSharedPreferences(any(), any()) } returns mapPrefs(values)
    }

    @Test
    fun `marked denial reads back and clears`() {
        store.markDenied(permission)
        assertTrue(store.wasDenied(permission))

        store.clear(permission)
        assertFalse(store.wasDenied(permission))
    }

    @Test
    fun `denial with rationale is recorded`() {
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = true)
        assertTrue(store.wasDenied(permission))
    }

    @Test
    fun `dismissed dialog without rationale is not recorded`() {
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = false)
        assertFalse(store.wasDenied(permission))
    }

    @Test
    fun `second denial without rationale keeps the earlier record`() {
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = true)
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = false)
        assertTrue(store.wasDenied(permission))
    }

    @Test
    fun `grant clears the record`() {
        context.recordPermissionResult(permission, granted = false, shouldShowRationale = true)
        context.recordPermissionResult(permission, granted = true, shouldShowRationale = false)
        assertFalse(store.wasDenied(permission))
    }

    @Test
    fun `silent denial records a permanent denial`() {
        context.recordPermissionResult(
            permission,
            granted = false,
            shouldShowRationale = false,
            silentlyDenied = true,
        )
        assertTrue(store.wasDenied(permission))
    }

    @Test
    fun `fast denial is silent and slow denial is a dialog`() {
        assertTrue(isSilentDenial(120))
        assertFalse(isSilentDenial(1_500))
        assertFalse(isSilentDenial(-1))
    }
}
