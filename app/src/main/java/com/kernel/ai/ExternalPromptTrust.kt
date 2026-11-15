package com.kernel.ai

import java.security.MessageDigest

/**
 * #1604 trust boundary for assistant text delivered to the exported [MainActivity] through intent
 * extras (`chat_input`, `quick_action_input[_encoded]`, `slot_reply_input`, `profile_text`).
 *
 * [MainActivity] is the launcher activity and therefore exported: any installed app can start it
 * with these extras. Only text from a trusted caller may run straight through the action-capable
 * model/tool loop; everything else is pre-filled into the existing input and runs only when the
 * user taps Send.
 */
internal enum class ExternalPromptTrust {
    /**
     * Jandal's own launches (widget text box, wake word, assistant overlay) and, in debug builds
     * only, the adb harness.
     */
    DIRECT,

    /** Another app: show the exact text in the existing input; nothing runs without an explicit Send. */
    PREFILL,
}

/** Linux uid of `adb shell` (`android.os.Process.SHELL_UID`). */
internal const val SHELL_UID = 2000

/** Linux uid of root (`android.os.Process.ROOT_UID`), used by `adb root` harness runs. */
internal const val ROOT_UID = 0

/** Debug-only intent extra carrying the adb harness token. */
internal const val ADB_HARNESS_TOKEN_EXTRA = "adb_harness_token"

/**
 * Debug-only file in the app's private `filesDir` holding the adb harness token. Only the app and
 * `adb shell run-as` (shell/root, debuggable builds only) can write or read it, so presenting the
 * matching token proves the launch came from someone with adb access to this device.
 */
internal const val ADB_HARNESS_TOKEN_FILE = "adb_harness_token"

private const val MIN_HARNESS_TOKEN_LENGTH = 16

/** Constant-time comparison of the token in the intent with the token stored by the harness. */
internal fun harnessTokenMatches(presented: String?, stored: String?): Boolean {
    if (presented == null || stored == null) return false
    if (presented.length < MIN_HARNESS_TOKEN_LENGTH) return false
    return MessageDigest.isEqual(presented.toByteArray(), stored.toByteArray())
}

/**
 * Classifies the caller that launched or re-delivered [MainActivity].
 *
 * [callerUid] comes from the platform (`Activity.launchedFromUid` / `ComponentCaller.uid`), which an
 * intent cannot spoof. It is `Process.INVALID_UID` unless the caller is this app or opted in to
 * share its identity — which `adb shell am start` cannot do, so in practice the shell is recognised
 * through [harnessTokenValid] (see [harnessTokenMatches]). Release builds trust neither the shell,
 * root nor the token, so release has no external auto-run path.
 */
internal fun classifyExternalPromptCaller(
    callerUid: Int,
    ownUid: Int,
    isDebugBuild: Boolean,
    harnessTokenValid: Boolean = false,
): ExternalPromptTrust = when {
    callerUid == ownUid -> ExternalPromptTrust.DIRECT
    isDebugBuild && (callerUid == SHELL_UID || callerUid == ROOT_UID || harnessTokenValid) -> ExternalPromptTrust.DIRECT
    else -> ExternalPromptTrust.PREFILL
}

/**
 * What [MainActivity] does with one intent's prompt extras. Exactly one of each direct/draft pair
 * is set for a non-blank input.
 */
internal data class ExternalPromptDelivery(
    /** Auto-submitted to Chat. */
    val chatQuery: String? = null,
    /** Pre-filled into the Chat composer; sent only on Send. */
    val chatDraft: String? = null,
    /** Auto-run in Actions. */
    val quickActionQuery: String? = null,
    val quickActionIsVoice: Boolean = false,
    /** Pre-filled into the Quick Action sheet; run only on its send button. */
    val quickActionDraft: String? = null,
    /** Auto-answers a pending Actions slot. */
    val slotReply: String? = null,
    /** Pre-filled into a pending slot's reply field; submitted only by the user. */
    val slotReplyDraft: String? = null,
    /** Debug-only ADB hook that saves the user profile; never honoured for untrusted callers. */
    val profileText: String? = null,
)

/** One-shot prompt extras belong to a fresh activity launch, never a restored instance. */
internal fun shouldResolvePromptExtrasOnActivityCreate(savedInstanceStatePresent: Boolean): Boolean =
    !savedInstanceStatePresent

internal fun resolveExternalPromptDelivery(
    chatInput: String?,
    quickActionInput: String?,
    quickActionInputEncoded: String?,
    quickActionIsVoice: Boolean,
    slotReplyInput: String?,
    profileText: String?,
    trust: ExternalPromptTrust,
    isDebugBuild: Boolean,
    decodeUriComponent: (String) -> String,
): ExternalPromptDelivery {
    val direct = trust == ExternalPromptTrust.DIRECT
    val chat = chatInput?.takeIf { it.isNotBlank() }
    val quickAction = quickActionInput?.takeIf { it.isNotBlank() }
        ?: quickActionInputEncoded?.takeIf { it.isNotBlank() }?.let(decodeUriComponent)?.takeIf { it.isNotBlank() }
    val slotReply = slotReplyInput?.takeIf { it.isNotBlank() }
    return ExternalPromptDelivery(
        chatQuery = chat.takeIf { direct },
        chatDraft = chat.takeUnless { direct },
        quickActionQuery = quickAction.takeIf { direct },
        // An untrusted caller cannot claim the voice path (spoken replies).
        quickActionIsVoice = direct && quickAction != null && quickActionIsVoice,
        quickActionDraft = quickAction.takeUnless { direct },
        slotReply = slotReply.takeIf { direct },
        slotReplyDraft = slotReply.takeUnless { direct },
        profileText = profileText?.takeIf { isDebugBuild && direct },
    )
}
