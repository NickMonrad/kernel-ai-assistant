package com.kernel.ai.core.inference.auth

import android.content.Intent
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for HuggingFace OAuth 2.0 PKCE authentication.
 *
 * The interface lives in :core:inference so that [ModelDownloadManager] and the feature
 * modules (onboarding, settings) can all depend on it without touching the AppAuth
 * implementation, which is confined to :app.
 *
 * Flow:
 * 1. Call [buildAuthIntent] to obtain an [Intent] that launches a Chrome Custom Tab for login.
 * 2. Launch the intent via [android.app.Activity.startActivityForResult] /
 *    [androidx.activity.result.ActivityResultLauncher].
 * 3. When the OAuth redirect returns (AppAuth's RedirectUriReceiverActivity delivers the
 *    result), pass the [Intent] to [handleAuthResponse] to exchange the code for a token.
 * 4. [isAuthenticated] and [username] update automatically.
 */
interface HuggingFaceAuthRepository {

    /** Emits `true` when a valid access token is stored locally. */
    val isAuthenticated: StateFlow<Boolean>

    /** HuggingFace username extracted from the OIDC id_token, or `null` if not signed in. */
    val username: StateFlow<String?>

    /**
     * Returns the stored HF access token, or `null` if the user is not signed in.
     * Must be called off the main thread when used in network code.
     */
    fun getAccessToken(): String?

    /**
     * Builds an [Intent] that starts the Authorization Code + PKCE flow via a Chrome Custom
     * Tab. The intent should be launched with [android.app.Activity.startActivityForResult] or
     * [androidx.activity.result.ActivityResultLauncher] with
     * [androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult].
     */
    fun buildAuthIntent(): Intent

    /**
     * Handles the redirect [Intent] returned by AppAuth's RedirectUriReceiverActivity.
     * Performs the token exchange (authorization code → access token) on the caller's
     * coroutine context and stores the result in EncryptedSharedPreferences.
     *
     * @return [Result.success] on success, [Result.failure] with the underlying exception otherwise.
     */
    suspend fun handleAuthResponse(intent: Intent): Result<Unit>

    /**
     * Clears the stored token and username, effectively signing the user out.
     * [isAuthenticated] transitions to `false` immediately.
     */
    fun signOut()
}
