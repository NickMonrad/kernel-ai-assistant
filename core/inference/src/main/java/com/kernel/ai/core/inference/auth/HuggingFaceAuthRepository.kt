package com.kernel.ai.core.inference.auth

import android.content.Intent
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Contract for HuggingFace OAuth 2.0 PKCE authentication.
 *
 * The interface lives in :core:inference so that [ModelDownloadManager] and the feature
 * modules (onboarding, settings) can all depend on it without touching the AppAuth
 * implementation, which is confined to :app.
 *
 * Flow:
 * 1. Call [startAuthFlow] from a button-click handler (main thread). AppAuth launches a
 *    Chrome Custom Tab and, on completion, delivers the result to [MainActivity] via
 *    [android.app.Activity.onNewIntent] using a [android.app.PendingIntent].
 * 2. [MainActivity.onNewIntent] detects the AppAuth response and calls [deliverAuthResponse].
 * 3. [deliverAuthResponse] exchanges the code for a token on a background thread and persists
 *    the result in EncryptedSharedPreferences.
 * 4. [isAuthenticated] and [username] update automatically.
 *
 * Using PendingIntent delivery (instead of ActivityResultLauncher) prevents Samsung's
 * aggressive memory management from killing AppAuth's AuthorizationManagementActivity
 * while the Chrome Custom Tab is open (observed on S23 Ultra / Android 16).
 */
interface HuggingFaceAuthRepository {

    /** Emits `true` when a valid access token is stored locally. */
    val isAuthenticated: StateFlow<Boolean>

    /**
     * Emits the outcome of each OAuth token exchange so that UI layers (Onboarding,
     * Settings) can react to success or failure without polling [isAuthenticated].
     */
    val authResult: SharedFlow<Result<Unit>>

    /** HuggingFace username extracted from the OIDC id_token, or `null` if not signed in. */
    val username: StateFlow<String?>

    /**
     * Returns the stored HF access token, or `null` if the user is not signed in.
     * Must be called off the main thread when used in network code.
     */
    fun getAccessToken(): String?

    /**
     * Starts the Authorization Code + PKCE flow by launching a Chrome Custom Tab.
     * AppAuth delivers the result back to [MainActivity] via a [android.app.PendingIntent],
     * which calls [onNewIntent] regardless of whether the activity was recreated.
     *
     * Must be called on the main thread (AppAuth requirement for Chrome Custom Tab setup).
     */
    fun startAuthFlow()

    /**
     * Delivers the OAuth redirect [Intent] received in [MainActivity.onNewIntent].
     * Performs the token exchange (authorization code → access token) on a background
     * dispatcher and stores the result in EncryptedSharedPreferences.
     *
     * Safe to call on the main thread — internally switches to [kotlinx.coroutines.Dispatchers.IO].
     */
    fun deliverAuthResponse(intent: Intent)

    /**
     * Handles the redirect [Intent] returned by AppAuth. Performs the token exchange
     * (authorization code → access token) on the caller's coroutine context and stores
     * the result in EncryptedSharedPreferences.
     *
     * Exposed for internal use by [deliverAuthResponse]; external callers should prefer
     * [deliverAuthResponse].
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
