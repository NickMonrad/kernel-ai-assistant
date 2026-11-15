package com.kernel.ai.nextcloudacceptance

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.core.memory.nextcloud.NextcloudCalendarCollection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextcloudSharingAcceptanceHarnessTest {
    @Test
    fun recipientCollectionSelectionUsesHrefSlugWhenServerChangesDisplayName() {
        val fixture = "J1548-0123456789abcdef"
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val owner = NextcloudCalendarCollection(
            href = "https://cloud.example/remote.php/dav/calendars/owner/${fixture.lowercase()}-${uuid}/",
            displayName = fixture,
            writable = true,
        )
        val malformed = NextcloudCalendarCollection(
            href = "https://cloud.example/remote.php/dav/calendars/recipient/${fixture.lowercase()}-not-a-uuid_shared_by_owner/",
            displayName = "Unrelated collection",
            writable = true,
        )
        val candidate = NextcloudCalendarCollection(
            href = "https://cloud.example/remote.php/dav/calendars/recipient/${fixture.lowercase()}-${uuid}_shared_by_owner/",
            displayName = "Server-generated shared task title",
            writable = true,
        )
        val unrelated = NextcloudCalendarCollection(
            href = "https://cloud.example/remote.php/dav/calendars/recipient/other-list/",
            displayName = fixture,
            writable = true,
        )

        val matches = selectFixtureCollections(fixture, listOf(unrelated, malformed, candidate))
        assertEquals(true, collectionMatchesFixture(fixture, owner.href))
        assertFalse(collectionMatchesFixture(fixture, malformed.href))

        assertEquals(listOf(candidate), matches)
        assertEquals("Server-generated shared task title", matches.single().displayName)
    }
}
