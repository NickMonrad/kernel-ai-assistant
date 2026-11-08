package com.kernel.ai.core.model.availability

/**
 * Represents the gated-model access status for a [KernelModel] that is gated on
 * HuggingFace. Persisted in DataStore; status decisions are made server-side
 * by the HuggingFace moderation system.
 *
 * - [NONE]: No status known — user can attempt to download. The backend will
 *   report the result (approval pending / denied / success).
 * - [APPROVAL_PENDING]: User has requested access, waiting for HF moderation.
 * - [APPROVED]: Access granted — download can proceed.
 * - [ACCESS_DENIED]: HF moderation rejected the access request.
 */
enum class GatedModelStatus {
    NONE,
    APPROVAL_PENDING,
    APPROVED,
    ACCESS_DENIED,
}
