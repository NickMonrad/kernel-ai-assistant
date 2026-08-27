package com.kernel.ai.feature.widget

import android.content.Context
import com.kernel.ai.core.memory.lists.ListsDataChanged
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

class ListsDataChangedTest {

    @Test
    fun `broadcast sends the lists data changed action`() {
        val context = mockk<Context>(relaxed = true)
        ListsDataChanged.broadcast(context)
        verify(exactly = 1) { context.sendBroadcast(any()) }
    }

    @Test
    fun `broadcast never throws even when sendBroadcast fails`() {
        val context = mockk<Context> {
            every { sendBroadcast(any()) } throws SecurityException("blocked")
        }
        // Must not propagate — a failed broadcast must never roll back a mutation.
        ListsDataChanged.broadcast(context)
    }
}
