package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.nextcloud.CalDavResponse
import com.kernel.ai.core.memory.nextcloud.CalDavTransport
import com.kernel.ai.core.memory.nextcloud.NextcloudAccount
import com.kernel.ai.core.memory.nextcloud.NextcloudAccountCredentials
import com.kernel.ai.core.memory.nextcloud.NextcloudConnectionException
import com.kernel.ai.core.memory.nextcloud.NextcloudCredentialStore
import com.kernel.ai.core.memory.nextcloud.NextcloudFailure
import com.kernel.ai.core.memory.nextcloud.NextcloudSyncAdapter
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import java.net.UnknownHostException
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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

    @Test
    fun `successful discovery persists normalized credentials after transient authentication`() = runTest {
        val store = FakeCredentialStore()
        val viewModel = viewModel(store, FakeTransport())
            .configured(" https://cloud.example/ ", " alice ", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(
            NextcloudAccountCredentials(
                account = NextcloudAccount("https://cloud.example", "alice"),
                appPassword = "fixture-app-password",
            ),
            store.saved,
        )
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `failed discovery does not persist newly entered credentials`() = runTest {
        val store = FakeCredentialStore()
        val viewModel = viewModel(store, FakeTransport(failureStatus = 401))
            .configured("https://cloud.example", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertNull(store.saved)
        assertEquals(0, store.saveCount)
        assertEquals(
            "Nextcloud rejected the credentials. Use a valid app password and username.",
            viewModel.state.value.message,
        )
    }

    @Test
    fun `settings surfaces a safe mapped transport failure`() = runTest {
        val store = FakeCredentialStore()
        val failure = NextcloudConnectionException(
            NextcloudFailure.Code.DNS,
            "Could not find the Nextcloud server. Check the server URL and network connection.",
            UnknownHostException("private.example"),
        )
        val viewModel = viewModel(store, FakeTransport(failure = failure))
            .configured("https://cloud.example", "alice", "fixture-app-password")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(NextcloudFailure.Code.DNS, failure.code)
        assertEquals(failure.message, viewModel.state.value.message)
        assertFalse(viewModel.state.value.message!!.contains("private.example"))
    }
    @Test
    fun `failed replacement preserves the previous saved account`() = runTest {
        val previous = NextcloudAccountCredentials(
            account = NextcloudAccount("https://old.example", "old-user"),
            appPassword = "old-fixture-password",
        )
        val store = FakeCredentialStore(previous)
        val viewModel = viewModel(store, FakeTransport(failureStatus = 401))
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
        val transport = FakeTransport()
        val viewModel = viewModel(store, transport)
            .configured("https://cloud.example", "alice", "")

        viewModel.connect()
        advanceUntilIdle()

        assertEquals(stored, store.saved)
        assertEquals(0, store.saveCount)
        assertEquals(
            "Basic " + Base64.getEncoder().encodeToString("alice:stored-fixture-password".toByteArray()),
            transport.lastAuthorization,
        )
    }

    private fun viewModel(store: FakeCredentialStore, transport: CalDavTransport): NextcloudSettingsViewModel {
        val listNameDao: ListNameDao = mockk()
        every { listNameDao.observeActiveLists() } returns flowOf(emptyList())
        val adapter = NextcloudSyncAdapter(
            accountStore = store,
            transport = transport,
            collectionBindings = mockk(relaxed = true),
            itemBindings = mockk(relaxed = true),
            listItemDao = mockk(relaxed = true),
            listNameDao = listNameDao,
            mutations = mockk(relaxed = true),
        )
        return NextcloudSettingsViewModel(adapter, listNameDao)
    }

    private fun NextcloudSettingsViewModel.configured(
        serverUrl: String,
        username: String,
        appPassword: String,
    ) = apply {
        setServerUrl(serverUrl)
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

        override fun save(serverUrl: String, username: String, appPassword: String) {
            saveCount += 1
            saved = NextcloudAccountCredentials(NextcloudAccount(serverUrl, username), appPassword)
        }

        override fun clear() {
            saved = null
        }
    }

    private class FakeTransport(
        private val failureStatus: Int? = null,
        private val failure: Throwable? = null,
    ) : CalDavTransport {
        var lastAuthorization: String? = null
        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String?,
        ): CalDavResponse {
            lastAuthorization = headers["Authorization"]
            failure?.let { throw it }
            if (failureStatus != null) {
                return CalDavResponse(failureStatus, emptyMap(), "", url)
            }
            return when {
                method == "GET" && url == "https://cloud.example/.well-known/caldav" ->
                    CalDavResponse(200, emptyMap(), "", "https://cloud.example/remote.php/dav")
                method == "PROPFIND" && url == "https://cloud.example/remote.php/dav" ->
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:"><d:response><d:href>/remote.php/dav</d:href><d:propstat><d:prop><d:current-user-principal><d:href>/remote.php/dav/principals/users/alice/</d:href></d:current-user-principal></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url == "https://cloud.example/remote.php/dav/principals/users/alice/" ->
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/principals/users/alice/</d:href><d:propstat><d:prop><c:calendar-home-set><d:href>/remote.php/dav/calendars/alice/</d:href></c:calendar-home-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
                method == "PROPFIND" && url == "https://cloud.example/remote.php/dav/calendars/alice/" ->
                    xmlResponse(
                        """
                        <d:multistatus xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav"><d:response><d:href>/remote.php/dav/calendars/alice/tasks/</d:href><d:propstat><d:prop><d:displayname>Tasks</d:displayname><d:resourcetype><d:collection/><c:calendar/></d:resourcetype><c:supported-calendar-component-set><c:comp name="VTODO"/></c:supported-calendar-component-set></d:prop></d:propstat></d:response></d:multistatus>
                        """,
                    )
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
