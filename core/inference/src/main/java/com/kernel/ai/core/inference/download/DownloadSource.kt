package com.kernel.ai.core.inference.download

/**
 * Source of a download request — used to distinguish auto-queued downloads from
 * user-initiated ones.
 *
 * - [AUTO_QUEUED]: Started by the system on startup (required models, tier-preferred models,
 *   and co-dependent vocabulary or auxiliary files). These cannot be cancelled via the UI.
 * - [USER_INITIATED]: Started by explicit user action. Can be cancelled.
 */
enum class DownloadSource {
    AUTO_QUEUED,
    USER_INITIATED,
}
