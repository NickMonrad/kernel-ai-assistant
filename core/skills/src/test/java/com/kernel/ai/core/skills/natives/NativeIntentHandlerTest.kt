package com.kernel.ai.core.skills.natives

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log
import com.kernel.ai.core.inference.EmbeddingEngine
import com.kernel.ai.core.memory.ContactAliasRepository
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.ScheduledAlarmDao
import com.kernel.ai.core.memory.repository.MemoryRepository
import io.mockk.coEvery
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalTime

class NativeIntentHandlerTest {
    private val context = mockk<Context>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)
    private val contactAliasRepository = mockk<ContactAliasRepository>(relaxed = true)

    private val handler = NativeIntentHandler(
        context = context,
        scheduledAlarmDao = mockk<ScheduledAlarmDao>(relaxed = true),
        listItemDao = mockk<ListItemDao>(relaxed = true),
        listNameDao = mockk<ListNameDao>(relaxed = true),
        contactAliasRepository = contactAliasRepository,
        memoryRepository = mockk<MemoryRepository>(relaxed = true),
        embeddingEngine = mockk<EmbeddingEngine>(relaxed = true),
    )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { context.contentResolver } returns contentResolver
    }

    @AfterEach
    fun tearDown() {
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

    private data class PhoneRow(
        val contactId: String,
        val displayName: String,
        val phoneNumber: String,
        val isPrimary: Boolean = false,
        val isSuperPrimary: Boolean = false,
        val phoneType: Int = ContactsContract.CommonDataKinds.Phone.TYPE_OTHER,
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
}
