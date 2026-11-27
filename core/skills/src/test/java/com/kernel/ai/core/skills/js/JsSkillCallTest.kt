package com.kernel.ai.core.skills.js

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JsSkillCallTest {

    @Test
    fun `renderer gone fails the call once with the interrupted error and returns true`() = runTest {
        val call = JsSkillCall("query-wikipedia")
        val pending = async { runCatching { call.await() } }
        var destroyCount = 0

        val handled = call.onRendererGone(didCrash = true, rendererPriority = 0) { destroyCount++ }

        assertTrue(handled)
        assertEquals(1, destroyCount)
        val error = pending.await().exceptionOrNull()
        assertTrue(error is JsSkillRendererGoneException)
        assertEquals(
            "The JS renderer was interrupted; please try again.",
            error?.message,
        )
    }

    @Test
    fun `late bridge result after renderer gone is ignored`() = runTest {
        val call = JsSkillCall("run-js")
        val pending = async { runCatching { call.await() } }

        call.onRendererGone(didCrash = false, rendererPriority = 1) {}
        val lateSucceeded = call.succeed("late answer")
        val lateFailed = call.fail(IllegalStateException("late error"))

        assertFalse(lateSucceeded)
        assertFalse(lateFailed)
        assertTrue(pending.await().exceptionOrNull() is JsSkillRendererGoneException)
    }

    @Test
    fun `renderer gone after a completed call keeps the result and still destroys the WebView`() = runTest {
        val call = JsSkillCall("query-wikipedia")
        assertTrue(call.succeed("Canberra"))
        var destroyCount = 0

        val handled = call.onRendererGone(didCrash = false, rendererPriority = 0) { destroyCount++ }

        assertTrue(handled)
        assertEquals(1, destroyCount)
        assertEquals("Canberra", call.await())
    }

    @Test
    fun `normal completion succeeds and a second result is rejected`() = runTest {
        val call = JsSkillCall("run-js")
        val pending = async { call.await() }

        assertTrue(call.succeed("42"))
        assertFalse(call.succeed("43"))

        assertEquals("42", pending.await())
        assertTrue(call.isCompleted)
    }

    @Test
    fun `load error completion is preserved over later renderer gone`() = runTest {
        val call = JsSkillCall("query-wikipedia")
        val pending = async { runCatching { call.await() } }

        call.fail(Exception("WebView load error: net::ERR"))
        call.onRendererGone(didCrash = true, rendererPriority = 0) {}

        assertEquals(
            "WebView load error: net::ERR",
            pending.await().exceptionOrNull()?.message,
        )
    }

    @Test
    fun `WebView is destroyed at most once across renderer-gone and finally`() {
        val call = JsSkillCall("run-js")
        var destroyCount = 0

        call.onRendererGone(didCrash = true, rendererPriority = 0) { destroyCount++ }
        call.destroyWebView { destroyCount++ }

        assertEquals(1, destroyCount)
    }
}
