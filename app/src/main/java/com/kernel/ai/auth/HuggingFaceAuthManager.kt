package com.kernel.ai.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.kernel.ai.BuildConfig
import com.kernel.ai.MainActivity
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private const val TAG = "KernelAI"
private const val PREFS_FILE_NAME = "hf_auth_prefs"
private const val KEY_ACCESS_TOKEN = "hf_access_token"
private const val KEY_USERNAME = "hf_username"

/**
 * Singleton that manages HuggingFace OAuth 2.0 PKCE authentication.
 *
 * - Tokens are stored in [EncryptedSharedPreferences] (AES-256-GCM key in Android Keystore).
 * - Uses AppAuth-Android for the Chrome Custom Tab PKCE flow — no WebView.
 * - Implements [HuggingFaceAuthRepository] so that feature modules and [ModelDownloadManager]
 *   interact with the interface only; AppAuth types are fully encapsulated here.
 *
 * HuggingFace OAuth app details:
 *   clientId   = [BuildConfig.HF_CLIENT_ID]  (public, no client secret)
 *   redirectUri = [BuildConfig.HF_REDIRECT_URI] (release: com.kernel.ai://oauth/callback,
 *                                                 debug:   com.kernel.ai.debug://oauth/callback)
 *   scopes     = openid profile gated-repos
 */
@Singleton
class HuggingFaceAuthManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : HuggingFaceAuthRepository {

    // -------------------------------------------------------------------------
    // Coroutine scope — single stable scope for all background work in this manager
    // -------------------------------------------------------------------------

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // -------------------------------------------------------------------------
    // Mutex — serialises concurrent sign-in / sign-out state writes
    // -------------------------------------------------------------------------

    private val authMutex = Mutex()

    // -------------------------------------------------------------------------
    // Encrypted preferences
    // -------------------------------------------------------------------------

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    // -------------------------------------------------------------------------
    // AppAuth service + configuration
    // -------------------------------------------------------------------------

    private val authService: AuthorizationService by lazy {
        AuthorizationService(context)
    }

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://huggingface.co/oauth/authorize"),
        Uri.parse("https://huggingface.co/oauth/token"),
    )

    // -------------------------------------------------------------------------
    // Observable state
    // -------------------------------------------------------------------------

    private val _isAuthenticated = MutableStateFlow(false)
    override val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _username = MutableStateFlow<String?>(null)
    override val username: StateFlow<String?> = _username.asStateFlow()

    private val _authResult = MutableSharedFlow<Result<Unit>>(extraBufferCapacity = 1)
    override val authResult: SharedFlow<Result<Unit>> = _authResult.asSharedFlow()

    init {
        // Restore persisted state off the main thread — EncryptedSharedPreferences initialises
        // MasterKey and the Keystore, which can block 100–500 ms on first use.
        managerScope.launch {
            val storedToken = runCatching { prefs.getString(KEY_ACCESS_TOKEN, null) }.getOrNull()
            val storedUser  = runCatching { prefs.getString(KEY_USERNAME, null) }.getOrNull()
            _isAuthenticated.value = !storedToken.isNullOrBlank()
            _username.value = storedUser
            Log.d(TAG, "HuggingFaceAuthManager: restored auth=${_isAuthenticated.value}, user=${_username.value}")
        }
    }

    // -------------------------------------------------------------------------
    // HuggingFaceAuthRepository implementation
    // -------------------------------------------------------------------------

    override fun getAccessToken(): String? =
        runCatching { prefs.getString(KEY_ACCESS_TOKEN, null) }.getOrNull()

    /**
     * Starts the Authorization Code + PKCE flow by launching a Chrome Custom Tab.
     * The OAuth result is delivered back to [MainActivity] via a [PendingIntent], which
     * calls [MainActivity.onNewIntent] regardless of whether the activity was recreated.
     *
     * This approach survives Samsung's aggressive memory management (observed on S23 Ultra /
     * Android 16) where AppAuth's AuthorizationManagementActivity could be killed while the
     * Custom Tab is open, preventing ActivityResultLauncher from firing.
     *
     * Must be called on the main thread (AppAuth requirement for Chrome Custom Tab setup).
     */
    override fun startAuthFlow() {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            BuildConfig.HF_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(BuildConfig.HF_REDIRECT_URI),
        )
            .setScopes("openid", "profile", "gated-repos")
            .build()

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val completionIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val cancelIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        authService.performAuthorizationRequest(
            request,
            PendingIntent.getActivity(context, REQUEST_CODE_COMPLETION, completionIntent, flags),
            PendingIntent.getActivity(context, REQUEST_CODE_CANCEL, cancelIntent, flags),
        )
    }

    /**
     * Delivers the OAuth redirect [Intent] received in [MainActivity.onNewIntent].
     * Performs the token exchange on [Dispatchers.IO], logs any failure, and emits the
     * outcome on [authResult] so that ViewModels can surface feedback to the UI.
     *
     * Safe to call on the main thread.
     */
    override fun deliverAuthResponse(intent: Intent) {
        managerScope.launch {
            val result = handleAuthResponse(intent)
            result.onFailure { e -> Log.e(TAG, "HF auth delivery failed: ${e.message}", e) }
            _authResult.tryEmit(result)
        }
    }

    /**
     * Exchanges the authorization code for an access token and persists the result.
     *
     * Structured in two stages:
     * 1. Token exchange — runs inside [suspendCancellableCoroutine] so the AppAuth callback
     *    can resume the coroutine with the raw token data.
     * 2. Persist + state update — runs under [authMutex] to serialise concurrent sign-in /
     *    sign-out operations.
     */
    override suspend fun handleAuthResponse(intent: Intent): Result<Unit> {
        val authResponse = AuthorizationResponse.fromIntent(intent)
        val authException = AuthorizationException.fromIntent(intent)

        if (authException != null) {
            Log.e(TAG, "HF OAuth error: ${authException.message}", authException)
            return Result.failure(authException)
        }
        if (authResponse == null) {
            val err = IllegalStateException("HF OAuth: no authorization response in intent")
            Log.e(TAG, err.message!!)
            return Result.failure(err)
        }

        // Stage 1 — perform the token exchange; extract token + username in the AppAuth callback.
        val tokenExchangeResult = suspendCancellableCoroutine<Result<Pair<String, String?>>> { continuation ->
            authService.performTokenRequest(authResponse.createTokenExchangeRequest()) { tokenResponse, tokenException ->
                if (tokenException != null) {
                    Log.e(TAG, "HF token exchange failed: ${tokenException.message}", tokenException)
                    continuation.resume(Result.failure(tokenException))
                    return@performTokenRequest
                }
                if (tokenResponse == null) {
                    val err = IllegalStateException("HF OAuth: null token response")
                    Log.e(TAG, err.message!!)
                    continuation.resume(Result.failure(err))
                    return@performTokenRequest
                }

                val accessToken = tokenResponse.accessToken
                if (accessToken.isNullOrBlank()) {
                    val err = IllegalStateException("HF token response contained no access token")
                    Log.e(TAG, err.message!!)
                    continuation.resume(Result.failure(err))
                    return@performTokenRequest
                }
                val username = extractUsername(tokenResponse.idToken)
                continuation.resume(Result.success(accessToken to username))
            }
        }

        // Stage 2 — persist and update observable state under the mutex.
        val (accessToken, username) = tokenExchangeResult.getOrElse { return Result.failure(it) }
        return authMutex.withLock {
            runCatching {
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, accessToken)
                    .apply { if (username != null) putString(KEY_USERNAME, username) }
                    .apply()
            }.onFailure { e ->
                Log.e(TAG, "HF auth: failed to persist token", e)
                return@withLock Result.failure(e)
            }
            _isAuthenticated.value = true
            _username.value = username
            Log.i(TAG, "HF auth success — user: $username")
            Result.success(Unit)
        }
    }

    override fun signOut() {
        managerScope.launch {
            authMutex.withLock {
                runCatching {
                    prefs.edit()
                        .remove(KEY_ACCESS_TOKEN)
                        .remove(KEY_USERNAME)
                        .apply()
                }.onFailure { e -> Log.e(TAG, "HF sign-out: failed to clear prefs", e) }
                _isAuthenticated.value = false
                _username.value = null
                Log.i(TAG, "HF auth: signed out")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Attempts to extract `preferred_username` (or `name`) from the OIDC id_token JWT payload.
     * Returns `null` if the token is absent or the claim is missing.
     */
    private fun extractUsername(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return runCatching {
            val parts = idToken.split(".")
            if (parts.size < 2) return null
            val payloadJson = String(
                Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP),
                Charsets.UTF_8,
            )
            val json = JSONObject(payloadJson)
            json.optString("preferred_username").takeIf { it.isNotBlank() }
                ?: json.optString("name").takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        const val REQUEST_CODE_COMPLETION = 1001
        const val REQUEST_CODE_CANCEL = 1002
    }
}
