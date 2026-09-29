package com.kernel.ai.feature.settings

import com.kernel.ai.core.memory.nextcloud.NextcloudShare
import com.kernel.ai.core.memory.nextcloud.NextcloudShareListing
import com.kernel.ai.core.memory.nextcloud.NextcloudSharePermission
import com.kernel.ai.core.memory.nextcloud.NextcloudSharee
import com.kernel.ai.core.memory.nextcloud.NextcloudShareeType
import com.kernel.ai.core.memory.nextcloud.NextcloudSharingOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NextcloudSharingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var operations: FakeOperations

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        operations = FakeOperations()
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `read-only collection loads shares and ignores mutation requests`() = runTest {
        val share = share()
        operations.listing = NextcloudShareListing(listOf(share), writable = false)
        val viewModel = NextcloudSharingViewModel(operations)

        viewModel.start("collection")
        advanceUntilIdle()
        viewModel.changePermission(share, NextcloudSharePermission.READ_ONLY)
        viewModel.remove(share)
        advanceUntilIdle()

        assertFalse(viewModel.state.value.writable)
        assertEquals(listOf(share), viewModel.state.value.shares)
        assertTrue(operations.setCalls.isEmpty())
        assertTrue(operations.removeCalls.isEmpty())
    }

    @Test
    fun `writable collection changes permission and reloads server state`() = runTest {
        val share = share(permission = NextcloudSharePermission.READ_ONLY)
        operations.listing = NextcloudShareListing(listOf(share), writable = true)
        val viewModel = NextcloudSharingViewModel(operations)

        viewModel.start("collection")
        advanceUntilIdle()
        viewModel.changePermission(share, NextcloudSharePermission.EDITABLE)
        advanceUntilIdle()

        assertEquals(
            listOf(Triple("collection", share.principal, NextcloudSharePermission.EDITABLE)),
            operations.setCalls,
        )
        assertEquals("Sharing updated.", viewModel.state.value.feedback)
        assertTrue(viewModel.state.value.writable)
    }

    @Test
    fun `search results preserve user and group types`() = runTest {
        val results = listOf(
            NextcloudSharee("principal:principals/users/bob", "Bob", NextcloudShareeType.USER),
            NextcloudSharee("principal:principals/groups/team", "Team", NextcloudShareeType.GROUP),
        )
        operations.searchResults = results
        val viewModel = NextcloudSharingViewModel(operations)

        viewModel.start("collection")
        advanceUntilIdle()
        viewModel.setQuery("te")
        advanceUntilIdle()

        assertEquals(results, viewModel.state.value.results)
        assertTrue(viewModel.state.value.results.any { it.type == NextcloudShareeType.GROUP })
    }

    private fun share(
        permission: NextcloudSharePermission = NextcloudSharePermission.EDITABLE,
    ) = NextcloudShare(
        principal = "principal:principals/users/bob",
        displayName = "Bob",
        type = NextcloudShareeType.USER,
        permission = permission,
        invitationAccepted = true,
    )

    private class FakeOperations : NextcloudSharingOperations {
        var listing = NextcloudShareListing(emptyList(), writable = true)
        var searchResults = emptyList<NextcloudSharee>()
        val setCalls = mutableListOf<Triple<String, String, NextcloudSharePermission>>()
        val removeCalls = mutableListOf<Pair<String, String>>()

        override suspend fun listShares(collectionId: String): Result<NextcloudShareListing> =
            Result.success(listing)

        override suspend fun searchSharees(collectionId: String, query: String): Result<List<NextcloudSharee>> =
            Result.success(searchResults)

        override suspend fun setShare(
            collectionId: String,
            principal: String,
            permission: NextcloudSharePermission,
        ): Result<Unit> {
            setCalls += Triple(collectionId, principal, permission)
            return Result.success(Unit)
        }

        override suspend fun removeShare(collectionId: String, principal: String): Result<Unit> {
            removeCalls += collectionId to principal
            return Result.success(Unit)
        }
    }
}
