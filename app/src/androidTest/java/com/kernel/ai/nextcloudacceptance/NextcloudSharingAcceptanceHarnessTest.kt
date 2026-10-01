package com.kernel.ai.nextcloudacceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.core.memory.nextcloud.NextcloudCalendarCollection
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextcloudSharingAcceptanceHarnessTest {
    @Test
    fun recipientCollectionSelectionUsesHrefSlugWhenServerChangesDisplayName() {
        val fixture = "J1548-0123456789abcdef"
        val candidate = NextcloudCalendarCollection(
            href = "https://cloud.example/remote.php/dav/calendars/recipient/${fixture.lowercase()}_shared_by_owner/",
            displayName = "Server-generated shared task title",
            writable = true,
        )
        val unrelated = NextcloudCalendarCollection(
            href = "https://cloud.example/remote.php/dav/calendars/recipient/other-list/",
            displayName = fixture,
            writable = true,
        )

        val matches = selectFixtureCollections(fixture, listOf(unrelated, candidate))

        assertEquals(listOf(candidate), matches)
        assertEquals("Server-generated shared task title", matches.single().displayName)
    }
}
