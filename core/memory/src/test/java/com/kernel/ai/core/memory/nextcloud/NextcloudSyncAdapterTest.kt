package com.kernel.ai.core.memory.nextcloud

import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.NextcloudCollectionBindingDao
import com.kernel.ai.core.memory.dao.NextcloudItemBindingDao
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.entity.NextcloudItemBindingEntity
import com.kernel.ai.core.memory.lists.ListChange
import com.kernel.ai.core.memory.lists.ListChangeOperation
import com.kernel.ai.core.memory.lists.VersionStamp
import com.kernel.ai.core.memory.repository.ListMutationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NextcloudSyncAdapterTest {
    @Test
    fun `read-only binding pulls and does not block later bindings`() = runTest {
        val readOnly = binding("readonly")
        val writable = binding("writable")
        val bindings = linkedMapOf(readOnly.collectionId to readOnly, writable.collectionId to writable)
        val itemBindings = linkedMapOf(
            readOnly.collectionId to itemBinding(readOnly.collectionId, "readonly-item"),
            writable.collectionId to itemBinding(writable.collectionId, "writable-item"),
        )
        val lists = mapOf(
            readOnly.collectionId to list(readOnly.collectionId),
            writable.collectionId to list(writable.collectionId),
        )
        val rows = mapOf(
            readOnly.collectionId to listItem(readOnly.collectionId, "readonly-item"),
            writable.collectionId to listItem(writable.collectionId, "writable-item"),
        )
        val pending = listOf(
            change("readonly", "readonly-change"),
            change("writable", "writable-change"),
        )
        val transport = RecordingTransport()
        val fixture = adapter(bindings, itemBindings, lists, rows, pending, transport)

        val result = fixture.adapter.syncAll()

        assertTrue(result is NextcloudSyncResult.Failure)
        assertEquals(
            NextcloudFailure.Code.PERMISSION,
            (result as NextcloudSyncResult.Failure).error.code,
        )
        assertTrue("readonly" in transport.reportedCollections)
        coVerify(exactly = 0) {
            fixture.mutations.acknowledgePushed(listOf("readonly-change"))
        }
        coVerify {
            fixture.mutations.acknowledgePushed(listOf("writable-change"))
        }
        assertTrue("writable" in transport.reportedCollections)
    }

    @Test
    fun `syncAll propagates cancellation and skips later bindings`() = runTest {
        val first = binding("writable")
        val later = binding("readonly")
        val bindings = linkedMapOf(first.collectionId to first, later.collectionId to later)
        val itemBindings = linkedMapOf(
            first.collectionId to itemBinding(first.collectionId, "writable-item"),
            later.collectionId to itemBinding(later.collectionId, "readonly-item"),
        )
        val lists = mapOf(
            first.collectionId to list(first.collectionId),
            later.collectionId to list(later.collectionId),
        )
        val rows = mapOf(
            first.collectionId to listItem(first.collectionId, "writable-item"),
            later.collectionId to listItem(later.collectionId, "readonly-item"),
        )
        val transport = RecordingTransport(cancelOnReportCollection = first.collectionId)
        val fixture = adapter(bindings, itemBindings, lists, rows, emptyList(), transport)

        var cancellation: CancellationException? = null
        try {
            fixture.adapter.syncAll()
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertEquals("cancelled", cancellation?.message)
        assertEquals(setOf(first.collectionId), transport.reportedCollections)
    }

    private data class Fixture(
        val adapter: NextcloudSyncAdapter,
        val mutations: ListMutationRepository,
    )

    private fun adapter(
        bindings: MutableMap<String, NextcloudCollectionBindingEntity>,
        itemBindings: MutableMap<String, NextcloudItemBindingEntity>,
        lists: Map<String, ListNameEntity>,
        rows: Map<String, ListItemEntity>,
        pending: List<ListChange>,
        transport: RecordingTransport,
    ): Fixture {
        val accountStore = mockk<NextcloudCredentialStore>()
        every { accountStore.read() } returns NextcloudAccountCredentials(
            NextcloudAccount("https://cloud.example", "alice"),
            "app-password",
        )
        val collectionDao = mockk<NextcloudCollectionBindingDao>()
        coEvery { collectionDao.getAll() } returns bindings.values.toList()
        coEvery { collectionDao.get(any()) } answers { bindings[firstArg()] }
        coEvery { collectionDao.upsert(any()) } answers { bindings[firstArg<NextcloudCollectionBindingEntity>().collectionId] = firstArg() }

        val itemDao = mockk<NextcloudItemBindingDao>()
        coEvery { itemDao.getAll(any()) } answers { itemBindings.values.filter { it.collectionId == firstArg() } }
        coEvery { itemDao.upsert(any()) } answers { itemBindings[firstArg<NextcloudItemBindingEntity>().collectionId] = firstArg() }

        val listItemDao = mockk<ListItemDao>()
        coEvery { listItemDao.getByItemId(any()) } returns null
        coEvery { listItemDao.getAllByListAnyLifecycle(any()) } answers { listOfNotNull(rows.values.firstOrNull { it.listId == firstArg<Long>() }) }

        val listNameDao = mockk<ListNameDao>()
        coEvery { listNameDao.getByCollectionId(any()) } answers { lists[firstArg()] }

        val mutations = mockk<ListMutationRepository>()
        coEvery { mutations.pendingChanges() } returns pending
        coEvery { mutations.importSnapshot(any(), any()) } returns mockk(relaxed = true)
        coEvery { mutations.acknowledgePushed(any()) } returns Unit

        return Fixture(
            adapter = NextcloudSyncAdapter(
                accountStore,
                transport,
                collectionDao,
                itemDao,
                listItemDao,
                listNameDao,
                mutations,
            ),
            mutations = mutations,
        )
    }

    private fun binding(id: String) = NextcloudCollectionBindingEntity(
        collectionId = id,
        remoteHref = "https://cloud.example/calendars/$id/",
        remoteTitle = id,
        remoteEtag = null,
        remoteLogicalClock = 1L,
        updatedAt = 1L,
    )
    private fun change(collectionId: String, changeId: String) = ListChange(
        changeId = changeId,
        collectionId = collectionId,
        targetId = "$collectionId-item",
        actorId = "test",
        sourceSequence = 1L,
        stamp = VersionStamp(1L, "test"),
        operation = ListChangeOperation.SET_ITEM_TEXT,
    )

    private fun list(collectionId: String) = ListNameEntity(
        id = if (collectionId == "readonly") 1L else 2L,
        name = collectionId,
        collectionId = collectionId,
        canonicalTitle = collectionId,
    )

    private fun document(uid: String): String = VTodoDocument.new(
        uid = uid,
        summary = "Item",
        checked = false,
        dueAt = null,
        parentUid = null,
        orderKey = "0",
    ).render()

    private fun itemBinding(collectionId: String, uid: String) = NextcloudItemBindingEntity(
        itemId = "$collectionId-item",
        collectionId = collectionId,
        remoteUid = uid,
        remoteHref = "https://cloud.example/calendars/$collectionId/$uid.ics",
        etag = "\"$uid-etag\"",
        rawVtodo = document(uid),
        remoteLogicalClock = 1L,
        deletedRemotely = false,
        updatedAt = 1L,
    )

    private fun listItem(collectionId: String, uid: String) = ListItemEntity(
        id = if (collectionId == "readonly") 1L else 2L,
        listId = if (collectionId == "readonly") 1L else 2L,
        text = if (collectionId == "readonly") "Local change" else "Item",
        itemId = "$collectionId-item",
        collectionId = collectionId,
    )
    private class RecordingTransport(
        private val cancelOnReportCollection: String? = null,
    ) : CalDavTransport {
        val reportedCollections = mutableSetOf<String>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): CalDavResponse {
            val collection = url.substringAfter("/calendars/").substringBefore('/')
            return when {
                method == "PUT" && collection == "readonly" ->
                    CalDavResponse(403, emptyMap(), "", url)
                method == "GET" ->
                    CalDavResponse(200, emptyMap(), "", "https://cloud.example/remote.php/dav")
                method == "PROPFIND" && url.endsWith("/remote.php/dav") ->
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url.contains("/principals/users/alice/") ->
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url.contains("/calendars/alice/") ->
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendars/readonly/</d:href><d:propstat><d:prop><d:displayname>readonly</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response><d:response><d:href>/calendars/writable/</d:href><d:propstat><d:prop><d:displayname>writable</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "REPORT" -> {
                    reportedCollections += collection
                    if (collection == cancelOnReportCollection) {
                        throw CancellationException("cancelled")
                    }
                    val uid = "$collection-item"
                    response(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/calendars/$collection/$uid.ics</d:href><d:propstat><d:prop><d:getetag>\"$uid-etag\"</d:getetag><c:calendar-data>${remoteDocument(uid).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</c:calendar-data></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                }
                else -> error("Unexpected CalDAV request: $method $url")
            }
        }

        private fun response(xml: String) = CalDavResponse(207, emptyMap(), xml.trimIndent(), "")
        private fun remoteDocument(uid: String): String = VTodoDocument.new(
            uid = uid,
            summary = "Item",
            checked = false,
            dueAt = null,
            parentUid = null,
            orderKey = "0",
        ).render()
    }

}
