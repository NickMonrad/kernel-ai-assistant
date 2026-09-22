package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.NextcloudCollectionBindingDao
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.NextcloudCollectionBindingEntity
import com.kernel.ai.core.memory.nextcloud.CalDavResponse
import com.kernel.ai.core.memory.nextcloud.CalDavTransport
import com.kernel.ai.core.memory.nextcloud.NextcloudAccount
import com.kernel.ai.core.memory.nextcloud.NextcloudAccountCredentials
import com.kernel.ai.core.memory.nextcloud.NextcloudConnectionException
import com.kernel.ai.core.memory.nextcloud.NextcloudCredentialStore
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudListState
import com.kernel.ai.core.memory.nextcloud.NextcloudListSyncSummary
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** The collection hrefs the fixture's CalDAV discovery advertises. */
private const val REMOTE_TASKS_HREF = "https://cloud.example/remote.php/dav/calendars/alice/tasks/"
private const val REMOTE_WORK_HREF = "https://cloud.example/remote.php/dav/calendars/alice/work/"

@OptIn(ExperimentalCoroutinesApi::class)
class NextcloudSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ── Address normalization ────────────────────────────────────────────────────────────────────

    @Test
    fun `bare host normalizes to https before discovery`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel().configured("cloud.example.com", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(
            NextcloudAccountCredentials(
                account = NextcloudAccount("https://cloud.example.com", "alice"),
                appPassword = "fixture-app-password",
            ),
            fixture.store.saved,
        )
        assertTrue(fixture.transport.requestedUrls.any { it == "https://cloud.example.com/.well-known/caldav" })
    }

    @Test
    fun `host with port and base path survive normalization`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
            .configured("cloud.example.com:8443/nextcloud", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals("https://cloud.example.com:8443/nextcloud", fixture.store.saved?.account?.serverUrl)
    }

    @Test
    fun `explicit https address stays https`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel().configured("https://cloud.example/", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals("https://cloud.example", fixture.store.saved?.account?.serverUrl)
        assertEquals(false, fixture.store.saved?.account?.allowInsecureHttp)
    }

    @Test
    fun `explicit http address is rejected inline while insecure http is disabled`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel().configured("http://cloud.example", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        val message = viewModel.state.value.addressError
        assertTrue(message != null && message.contains("Plain HTTP is disabled"))
        assertNull(fixture.store.saved)
        assertEquals(0, fixture.store.saveCount)
        assertTrue(fixture.transport.requestedUrls.isEmpty(), "no request should reach the plaintext endpoint")
    }

    @Test
    fun `enabling insecure http permits the explicit http endpoint and records the opt-in`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
            .configured("http://cloud.example", "alice", "fixture-app-password")
            .apply { setAllowInsecureHttp(true) }

        viewModel.connect()
        advanceUntilIdle()

        assertNull(viewModel.state.value.addressError)
        assertEquals("http://cloud.example", fixture.store.saved?.account?.serverUrl)
        assertEquals(true, fixture.store.saved?.account?.allowInsecureHttp)
    }

    @Test
    fun `enabling insecure http leaves the https default for a bare host`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
            .configured("cloud.example.com", "alice", "fixture-app-password")
            .apply { setAllowInsecureHttp(true) }

        viewModel.connect()
        advanceUntilIdle()

        assertEquals("https://cloud.example.com", fixture.store.saved?.account?.serverUrl)
    }

    // ── Credential persistence ───────────────────────────────────────────────────────────────────

    @Test
    fun `successful discovery persists normalized credentials after transient authentication`() = runTest {
        val fixture = Fixture()
        val viewModel = fixture.viewModel()
            .configured(" https://cloud.example/ ", " alice ", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(
            NextcloudAccountCredentials(
                account = NextcloudAccount("https://cloud.example", "alice"),
                appPassword = "fixture-app-password",
            ),
            fixture.store.saved,
        )
        assertEquals(1, fixture.store.saveCount)
    }

    @Test
    fun `failed discovery does not persist newly entered credentials`() = runTest {
        val fixture = Fixture(transport = FakeTransport(failureStatus = 401))
        val viewModel = fixture.viewModel()
            .configured("https://cloud.example", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertNull(fixture.store.saved)
        assertEquals(0, fixture.store.saveCount)
        assertEquals(
            "Nextcloud rejected the credentials. Use a valid app password and username.",
            viewModel.state.value.feedback,
        )
        assertTrue(viewModel.state.value.authenticationFailed)
    }

    @Test
    fun `settings surfaces a safe mapped transport failure`() = runTest {
        val failure = NextcloudConnectionException(
            NextcloudFailure.Code.DNS,
            "Could not find the Nextcloud server. Check the server URL and network connection.",
            UnknownHostException("private.example"),
        )
        val fixture = Fixture(transport = FakeTransport(failure = failure))
        val viewModel = fixture.viewModel()
            .configured("https://cloud.example", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(failure.message, viewModel.state.value.feedback)
        assertFalse(viewModel.state.value.feedback!!.contains("private.example"))
    }

    @Test
    fun `failed replacement preserves the previous saved account`() = runTest {
        val previous = NextcloudAccountCredentials(
            account = NextcloudAccount("https://old.example", "old-user"),
            appPassword = "old-fixture-password",
        )
        val store = FakeCredentialStore(previous)
        val viewModel = Fixture(store = store, transport = FakeTransport(failureStatus = 401)).viewModel()
            .configured("https://new.example", "new-user", "new-fixture-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(previous, store.saved)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `matching saved account reuses stored credential when password is blank`() = runTest {
        val stored = NextcloudAccountCredentials(
            account = NextcloudAccount("https://cloud.example", "alice"),
            appPassword = "stored-fixture-password",
        )
        val store = FakeCredentialStore(stored)
        val fixture = Fixture(store = store)
        val viewModel = fixture.viewModel().configured("https://cloud.example", "alice", "")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(stored, store.saved)
        assertEquals(0, store.saveCount)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("alice:stored-fixture-password".toByteArray()),
            fixture.transport.lastAuthorization,
        )
    }

    // ── Credential form visibility ───────────────────────────────────────────────────────────────

    @Test
    fun `connected account shows a summary and hides the credential form`() = runTest {
        val fixture = Fixture(store = FakeCredentialStore(connectedAccount()))
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertFalse(state.showCredentialForm)
        assertEquals("https://cloud.example", state.connected?.serverUrl)
        assertEquals("alice", state.connected?.username)
        assertEquals("", state.appPassword, "the saved app password is never read back into UI state")
    }

    @Test
    fun `edit account reopens the form without exposing the stored password`() = runTest {
        val fixture = Fixture(store = FakeCredentialStore(connectedAccount()))
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.editAccount()

        val state = viewModel.state.value
        assertTrue(state.showCredentialForm)
        assertEquals("https://cloud.example", state.address)
        assertEquals("alice", state.username)
        assertEquals("", state.appPassword)
    }

    @Test
    fun `cancelling an edit restores the connected summary`() = runTest {
        val fixture = Fixture(store = FakeCredentialStore(connectedAccount()))
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        viewModel.editAccount()
        viewModel.cancelEditing()

        assertFalse(viewModel.state.value.showCredentialForm)
        assertEquals("", viewModel.state.value.appPassword)
    }

    @Test
    fun `expired authentication exposes reconnect while the account stays stored`() = runTest {
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            transport = FakeTransport(failureStatus = 401),
        )
        val viewModel = fixture.viewModel()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.authenticationFailed)
        assertEquals("https://cloud.example", fixture.store.saved?.account?.serverUrl)
        assertFalse(viewModel.state.value.showCredentialForm, "reconnect is a single explicit action")
    }

    // ── Sections, search and filters ─────────────────────────────────────────────────────────────

    @Test
    fun `bound lists appear once in connected and unbound lists keep their own sections`() = runTest {
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            lists = MutableStateFlow(
                listOf(list(1L, "Shopping", "collection-shopping"), list(2L, "Holiday packing", "collection-holiday")),
            ),
            summaries = MutableStateFlow(
                listOf(summary("collection-shopping", REMOTE_TASKS_HREF)),
            ),
        )
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)

        val rendered = sections()
        assertEquals(listOf("Shopping"), rendered.connected.map { it.displayName })
        assertEquals(listOf("Holiday packing"), rendered.jandalOnly.map { it.displayName })
        assertEquals(listOf("Work tasks", "Groceries"), rendered.nextcloudOnly.map { it.displayName })
        assertEquals(
            NextcloudListState.UP_TO_DATE,
            rendered.connected.single().syncState,
        )
    }

    @Test
    fun `search and state filters compose across sections`() = runTest {
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            lists = MutableStateFlow(
                listOf(
                    list(1L, "Shopping", "collection-shopping"),
                    list(2L, "Hardware store", "collection-hardware"),
                    list(3L, "Holiday packing", "collection-holiday"),
                ),
            ),
            summaries = MutableStateFlow(
                listOf(
                    summary("collection-shopping", REMOTE_TASKS_HREF),
                    summary(
                        "collection-hardware",
                        REMOTE_WORK_HREF,
                        failureCode = NextcloudFailure.Code.NETWORK.name,
                    ),
                ),
            ),
        )
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)

        viewModel.setFilter(NextcloudListFilter.NEEDS_ATTENTION)
        assertEquals(listOf("Hardware store"), sections().connected.map { it.displayName })
        assertTrue(sections().jandalOnly.isEmpty())
        assertTrue(sections().nextcloudOnly.isEmpty())

        viewModel.setQuery("shop")
        assertTrue(sections().connected.isEmpty(), "search composes with the state filter")

        viewModel.setFilter(NextcloudListFilter.ALL)
        assertEquals(listOf("Shopping"), sections().connected.map { it.displayName })

        viewModel.setQuery("")
        viewModel.setFilter(NextcloudListFilter.NEXTCLOUD_ONLY)
        assertEquals(
            listOf("Groceries"),
            sections().nextcloudOnly.map { it.displayName },
            "a bound list is not offered again as a Nextcloud-only candidate",
        )

        viewModel.setQuery("gro")
        assertEquals(listOf("Groceries"), sections().nextcloudOnly.map { it.displayName })

        viewModel.setFilter(NextcloudListFilter.JANDAL_ONLY)
        assertTrue(sections().nextcloudOnly.isEmpty(), "a Nextcloud-only list is not a Jandal-only list")
        viewModel.setQuery("holiday")
        assertEquals(listOf("Holiday packing"), sections().jandalOnly.map { it.displayName })
    }

    @Test
    fun `a stopped list reports sync off`() = runTest {
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            lists = MutableStateFlow(listOf(list(1L, "Shopping", "collection-shopping"))),
            summaries = MutableStateFlow(
                listOf(summary("collection-shopping", REMOTE_TASKS_HREF, syncEnabled = false)),
            ),
        )
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)

        assertEquals(NextcloudListState.SYNC_OFF, sections().connected.single().syncState)
    }

    // ── Per-list lifecycle ───────────────────────────────────────────────────────────────────────

    @Test
    fun `stopping sync keeps the binding and resumes through it`() = runTest {
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            lists = MutableStateFlow(listOf(list(1L, "Shopping", "collection-shopping"))),
            summaries = MutableStateFlow(
                listOf(summary("collection-shopping", REMOTE_TASKS_HREF)),
            ),
            bindings = linkedMapOf("collection-shopping" to binding("collection-shopping")),
        )
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)
        val item = sections().connected.single()

        viewModel.stopSync(item)
        advanceUntilIdle()

        coVerify { fixture.collectionBindings.setSyncEnabled("collection-shopping", false, any()) }
        coVerify(exactly = 0) { fixture.collectionBindings.delete(any()) }
        assertEquals("Nextcloud sync stopped for Shopping", viewModel.state.value.feedback)

        viewModel.resumeSync(item)
        advanceUntilIdle()

        coVerify { fixture.collectionBindings.setSyncEnabled("collection-shopping", true, any()) }
    }

    @Test
    fun `a failed list exposes a retry that reuses its binding`() = runTest {
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            lists = MutableStateFlow(listOf(list(1L, "Shopping", "collection-shopping"))),
            summaries = MutableStateFlow(
                listOf(
                    summary(
                        "collection-shopping",
                        REMOTE_TASKS_HREF,
                        failureCode = NextcloudFailure.Code.NETWORK.name,
                    ),
                ),
            ),
            bindings = linkedMapOf(
                "collection-shopping" to binding(
                    "collection-shopping",
                    failureCode = NextcloudFailure.Code.NETWORK.name,
                ),
            ),
        )
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)

        viewModel.retry(sections().connected.single())
        advanceUntilIdle()

        assertEquals("List synced with Nextcloud", viewModel.state.value.feedback)
        coVerify { fixture.collectionBindings.recordSyncOutcome("collection-shopping", null, null) }
    }

    @Test
    fun `a failed list stops to sync off and resumes into normal reconciliation`() = runTest {
        val unreachable = NextcloudConnectionException(
            NextcloudFailure.Code.DNS,
            "Could not find the Nextcloud server. Check the server URL and network connection.",
        )
        val fixture = Fixture(
            store = FakeCredentialStore(connectedAccount()),
            transport = FakeTransport(failure = unreachable),
            lists = MutableStateFlow(listOf(list(1L, "Shopping", "collection-shopping"))),
            summaries = MutableStateFlow(listOf(summary("collection-shopping", REMOTE_TASKS_HREF))),
            bindings = linkedMapOf("collection-shopping" to binding("collection-shopping")),
        )
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)
        advanceUntilIdle()   // the initial reconciliation fails and records the failure

        assertEquals(NextcloudListState.NEEDS_ATTENTION, sections().connected.single().syncState)

        viewModel.stopSync(sections().connected.single())
        advanceUntilIdle()

        // Stop must win over the recorded failure, otherwise Resume stays unreachable.
        assertEquals(NextcloudListState.SYNC_OFF, sections().connected.single().syncState)

        fixture.transport.failure = null   // the server is reachable again
        viewModel.resumeSync(sections().connected.single())
        advanceUntilIdle()

        coVerify { fixture.collectionBindings.setSyncEnabled("collection-shopping", true, any()) }
        assertEquals("List synced with Nextcloud", viewModel.state.value.feedback)
        assertEquals(
            NextcloudListState.UP_TO_DATE,
            sections().connected.single().syncState,
            "a successful resume reconciles the list back to a normal state",
        )
    }

    // ── Contextual first-time setup ──────────────────────────────────────────────────────────────

    @Test
    fun `contextual setup continues the initiating list binding after connecting`() = runTest {
        val shopping = list(7L, "Shopping", "collection-shopping")
        val fixture = Fixture(lists = MutableStateFlow(listOf(shopping)))
        coEvery { fixture.listNameDao.getById(7L) } returns shopping
        val viewModel = fixture.viewModel()

        viewModel.setPendingList(7L, "Shopping")
        viewModel.configured("cloud.example.com", "alice", "fixture-app-password")
        viewModel.connect()
        advanceUntilIdle()

        assertEquals("List synced with Nextcloud", viewModel.state.value.feedback)
        assertTrue(viewModel.state.value.pendingSetupCompleted)
        coVerify {
            fixture.collectionBindings.upsert(
                match { binding ->
                    binding.collectionId == "collection-shopping" &&
                        binding.remoteTitle == "Shopping" &&
                        binding.syncEnabled
                },
            )
        }
        assertTrue(
            fixture.transport.mkcalendarBodies.any { it.contains("<d:displayname>Shopping</d:displayname>") },
            "the remote collection keeps the clean Jandal list title",
        )
    }

    @Test
    fun `cancelled contextual setup leaves the list local-only with no binding`() = runTest {
        val shopping = list(7L, "Shopping", "collection-shopping")
        val fixture = Fixture(lists = MutableStateFlow(listOf(shopping)))
        coEvery { fixture.listNameDao.getById(7L) } returns shopping
        val viewModel = fixture.viewModel()

        viewModel.setPendingList(7L, "Shopping")
        advanceUntilIdle()

        assertEquals("Shopping", viewModel.state.value.pendingListName)
        assertFalse(viewModel.state.value.pendingSetupCompleted)
        coVerify(exactly = 0) { fixture.collectionBindings.upsert(any()) }
        assertEquals(0, fixture.store.saveCount)
    }

    @Test
    fun `failed contextual setup leaves the list local-only and reports the failure`() = runTest {
        val shopping = list(7L, "Shopping", "collection-shopping")
        val fixture = Fixture(
            lists = MutableStateFlow(listOf(shopping)),
            transport = FakeTransport(failMkcalendar = true),
        )
        coEvery { fixture.listNameDao.getById(7L) } returns shopping
        val viewModel = fixture.viewModel()

        viewModel.setPendingList(7L, "Shopping")
        viewModel.configured("cloud.example.com", "alice", "fixture-app-password")
        viewModel.connect()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.pendingSetupCompleted)
        assertNull(viewModel.state.value.pendingListId, "the pending intent is not retried silently")
        coVerify(exactly = 0) {
            fixture.collectionBindings.upsert(any())
        }
        assertTrue(fixture.bindings.isEmpty(), "a failed setup must not persist a partial binding")
        assertEquals("https://cloud.example.com", fixture.store.saved?.account?.serverUrl)
    }

    @Test
    fun `sync with nextcloud defers to setup when no account is configured`() = runTest {
        val shopping = list(7L, "Shopping", "collection-shopping")
        val fixture = Fixture(lists = MutableStateFlow(listOf(shopping)))
        val viewModel = fixture.viewModel()
        val sections = fixture.observeSections(viewModel, this)

        viewModel.syncWithNextcloud(sections().jandalOnly.single())

        assertEquals(7L, viewModel.state.value.pendingListId)
        assertEquals("Shopping", viewModel.state.value.pendingListName)
        coVerify(exactly = 0) { fixture.collectionBindings.upsert(any()) }
    }

    // ── Fixture ──────────────────────────────────────────────────────────────────────────────────

    private class Fixture(
        val store: FakeCredentialStore = FakeCredentialStore(),
        val transport: FakeTransport = FakeTransport(),
        val lists: MutableStateFlow<List<ListNameEntity>> = MutableStateFlow(emptyList()),
        val summaries: MutableStateFlow<List<NextcloudListSyncSummary>> = MutableStateFlow(emptyList()),
        val bindings: MutableMap<String, NextcloudCollectionBindingEntity> = linkedMapOf(),
    ) {
        val listNameDao: ListNameDao = mockk(relaxed = true)
        val collectionBindings: NextcloudCollectionBindingDao = mockk(relaxed = true)

        init {
            every { listNameDao.observeActiveLists() } returns lists
            every { collectionBindings.observeSyncSummaries() } returns summaries
            // Backed by a real map so a relaxed mock can never invent or hide a binding.
            coEvery { collectionBindings.get(any()) } answers { bindings[firstArg<String>()] }
            coEvery { collectionBindings.getAll() } answers { bindings.values.toList() }
            coEvery { collectionBindings.upsert(any()) } answers {
                val binding = firstArg<NextcloudCollectionBindingEntity>()
                bindings[binding.collectionId] = binding
            }
            // Both stores are kept in step so the flow the UI reads reflects the durable writes,
            // exactly as the real DAO projection would.
            coEvery { collectionBindings.setSyncEnabled(any(), any(), any()) } answers {
                val key = firstArg<String>()
                bindings[key]?.let { bindings[key] = it.copy(syncEnabled = secondArg()) }
                summaries.value = summaries.value.map {
                    if (it.collectionId == key) it.copy(syncEnabled = secondArg()) else it
                }
            }
            coEvery { collectionBindings.recordSyncOutcome(any(), any(), any()) } answers {
                val key = firstArg<String>()
                bindings[key]?.let { bindings[key] = it.copy(lastFailureCode = secondArg()) }
                summaries.value = summaries.value.map {
                    if (it.collectionId == key) it.copy(lastFailureCode = secondArg()) else it
                }
            }
            coEvery { collectionBindings.delete(any()) } answers {
                val key = firstArg<String>()
                bindings.remove(key)
                summaries.value = summaries.value.filterNot { it.collectionId == key }
            }
        }
        private val adapter = NextcloudSyncAdapter(
            accountStore = store,
            transport = transport,
            collectionBindings = collectionBindings,
            itemBindings = mockk(relaxed = true),
            listItemDao = mockk(relaxed = true),
            listNameDao = listNameDao,
            mutations = mockk(relaxed = true),
        )

        fun viewModel() = NextcloudSettingsViewModel(adapter, listNameDao)

        /**
         * Subscribes the section flow the way the screen does, and returns a reader for its latest
         * value once both Room-backed flows have replayed.
         */
        fun observeSections(
            viewModel: NextcloudSettingsViewModel,
            scope: TestScope,
        ): suspend () -> NextcloudListSections {
            // backgroundScope: the collector must not keep runTest waiting for an active child.
            scope.backgroundScope.launch { viewModel.sections.collect { } }
            return {
                scope.advanceUntilIdle()
                viewModel.sections.value
            }
        }
    }

    private fun connectedAccount() = NextcloudAccountCredentials(
        account = NextcloudAccount("https://cloud.example", "alice"),
        appPassword = "stored-fixture-password",
    )

    private fun list(id: Long, name: String, collectionId: String) = ListNameEntity(
        id = id,
        name = name,
        collectionId = collectionId,
    )

    private fun binding(
        collectionId: String,
        syncEnabled: Boolean = true,
        failureCode: String? = null,
    ) = NextcloudCollectionBindingEntity(
        collectionId = collectionId,
        remoteHref = REMOTE_TASKS_HREF,
        remoteTitle = collectionId,
        remoteEtag = null,
        remoteLogicalClock = 1L,
        updatedAt = 1L,
        syncEnabled = syncEnabled,
        lastFailureCode = failureCode,
    )

    private fun summary(
        collectionId: String,
        remoteHref: String,
        syncEnabled: Boolean = true,
        failureCode: String? = null,
    ) = NextcloudListSyncSummary(collectionId, remoteHref, syncEnabled, failureCode)

    private fun NextcloudSettingsViewModel.configured(
        address: String,
        username: String,
        appPassword: String,
    ) = apply {
        setAddress(address)
        setUsername(username)
        setAppPassword(appPassword)
    }

    private class FakeCredentialStore(
        initial: NextcloudAccountCredentials? = null,
    ) : NextcloudCredentialStore {
        var saved: NextcloudAccountCredentials? = initial
            private set
        var saveCount: Int = 0
            private set

        override fun read(): NextcloudAccountCredentials? = saved

        override fun save(serverUrl: String, username: String, appPassword: String, allowInsecureHttp: Boolean) {
            saveCount += 1
            saved = NextcloudAccountCredentials(
                NextcloudAccount(serverUrl, username, allowInsecureHttp),
                appPassword,
            )
        }

        override fun clear() {
            saved = null
        }
    }

    private class FakeTransport(
        private val failureStatus: Int? = null,
        var failure: Throwable? = null,
        private val failMkcalendar: Boolean = false,
    ) : CalDavTransport {
        var lastAuthorization: String? = null
        val requestedUrls = mutableListOf<String>()
        val mkcalendarBodies = mutableListOf<String>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): CalDavResponse {
            lastAuthorization = headers["Authorization"]
            requestedUrls += url
            failure?.let { throw it }
            if (failureStatus != null) {
                return CalDavResponse(failureStatus, emptyMap(), "", url)
            }
            val server = url.substringBefore("/.well-known").substringBefore("/remote.php")
            return when {
                method == "GET" && url.endsWith("/.well-known/caldav") ->
                    CalDavResponse(200, emptyMap(), "", "$server/remote.php/dav")
                method == "PROPFIND" && url == "$server/remote.php/dav" ->
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url.endsWith("/principals/users/alice/") ->
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url.endsWith("/calendars/alice/") ->
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/calendars/alice/tasks/</d:href><d:propstat><d:prop><d:displayname>Tasks</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response><d:response><d:href>/remote.php/dav/calendars/alice/work/</d:href><d:propstat><d:prop><d:displayname>Work tasks</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response><d:response><d:href>/remote.php/dav/calendars/alice/groceries/</d:href><d:propstat><d:prop><d:displayname>Groceries</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "MKCALENDAR" -> {
                    mkcalendarBodies += body.orEmpty()
                    if (failMkcalendar) {
                        CalDavResponse(507, emptyMap(), "", url)
                    } else {
                        CalDavResponse(201, emptyMap(), "", url)
                    }
                }
                method == "REPORT" -> xmlResponse("<d:multistatus xmlns:d=\"DAV:\"></d:multistatus>")
                else -> error("Unexpected discovery request: $method $url")
            }
        }

        private fun xmlResponse(body: String) = CalDavResponse(
            status = 207,
            headers = mapOf("content-type" to "application/xml"),
            body = body.trimIndent(),
            finalUrl = "",
        )
    }
}
