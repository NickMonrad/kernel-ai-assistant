package com.kernel.ai.core.memory.nextcloud

/**
 * Bound-list sharing operations exposed to the settings surface.
 *
 * This is deliberately limited to Nextcloud VTODO sharing; it is not a provider abstraction.
 */
interface NextcloudSharingOperations {
    suspend fun listShares(collectionId: String): Result<NextcloudShareListing>

    suspend fun searchSharees(collectionId: String, query: String): Result<List<NextcloudSharee>>

    suspend fun setShare(
        collectionId: String,
        principal: String,
        permission: NextcloudSharePermission,
    ): Result<Unit>

    suspend fun removeShare(collectionId: String, principal: String): Result<Unit>
}
