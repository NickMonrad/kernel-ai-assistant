package com.kernel.ai.core.skills.natives

import android.content.Context
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.ContactAliasRepository
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.repository.MemoryRepository
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.time.LocalTime

class NativeIntentHandlerTest {

    private val handler = NativeIntentHandler(
        context = mockk<Context>(relaxed = true),
        scheduledAlarmDao = mockk<ScheduledAlarmDao>(relaxed = true),
        listItemDao = mockk<ListItemDao>(relaxed = true),
        listNameDao = mockk<ListNameDao>(relaxed = true),
        contactAliasRepository = mockk<ContactAliasRepository>(relaxed = true),
        memoryRepository = mockk<MemoryRepository>(relaxed = true),
        embeddingEngine = mockk<EmbeddingEngine>(relaxed = true),
    )

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
    fun `resolveTime recovers flattened oclock format`() {
        val method = NativeIntentHandler::class.java.getDeclaredMethod(
            "resolveTime",
            String::class.java,
        ).apply { isAccessible = true }

        val resolved = method.invoke(handler, "80:00") as LocalTime?

        assertNotNull(resolved)
        assertEquals(LocalTime.of(8, 0), resolved)
    }
}
