package com.kernel.ai.feature.widget

import android.content.Context
import android.content.Intent
import com.kernel.ai.core.memory.lists.ListsDataChanged
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListsWidgetReceiverTest {

    @BeforeEach
    fun setUp() { mockkObject(ListsWidgetRefresher) }

    @AfterEach
    fun tearDown() { unmockkObject(ListsWidgetRefresher) }

    @Test
    fun `refreshes widget on the lists data changed broadcast`() {
        val context = mockk<Context>(relaxed = true)
        // Intent.getAction() is a stubbed no-op under the JVM unit-test runtime, so the action
        // must be mocked for the receiver's `intent.action == ACTION` gate to pass.
        val intent = mockk<Intent> { every { action } returns ListsDataChanged.ACTION }
        ListsWidgetReceiver().onReceive(context, intent)
        verify { ListsWidgetRefresher.refresh(context) }
    }

    @Test
    fun `ignores unrelated actions`() {
        val context = mockk<Context>(relaxed = true)
        ListsWidgetReceiver().onReceive(context, Intent("com.kernel.ai.action.SOMETHING_ELSE"))
        verify(exactly = 0) { ListsWidgetRefresher.refresh(any()) }
    }
}
