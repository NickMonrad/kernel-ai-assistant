package com.kernel.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.collectAsState
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.navigation.KernelNavHost
import dagger.hilt.android.AndroidEntryPoint
import com.kernel.ai.assistant.WakeWordService
import com.kernel.ai.core.voice.WakeWordPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val PREFS_RUNTIME_PERMISSIONS = "runtime_permissions"
        private const val KEY_ONBOARDING_PERMISSIONS_REQUESTED = "onboarding_permissions_requested"
    }

    @Inject lateinit var authRepository: HuggingFaceAuthRepository

    /** Injected for ADB `--es profile_text` test support — triggers profile parse + logcat output. */
    @Inject lateinit var wakeWordPreferences: WakeWordPreferences

    @Inject lateinit var userProfileRepository: UserProfileRepository

    @Inject lateinit var chatPreferences: ChatPreferences

    @Inject lateinit var favouriteShortcutRepository: com.kernel.ai.core.memory.shortcut.FavouriteShortcutRepository
    @Inject lateinit var recentShortcutTracker: com.kernel.ai.core.memory.shortcut.RecentShortcutTracker

    /** Bridges ADB `--es chat_input` extras (onCreate + onNewIntent) into the nav graph. */
    private val adbChatInput = mutableStateOf<String?>(null)

    /** One-shot widget/Side-key query delivered via onCreate or onNewIntent.
     *  The [serial] increments on every delivery so [LaunchedEffect] re-fires
     *  even when the query text is identical to the previous one. */
    private data class QuickActionRequest(val query: String, val isVoice: Boolean, val serial: Int)


    /** Bridges ADB `--es quick_action_input` extras into ActionsViewModel.executeAction(). */
    private val adbQuickActionInput = mutableStateOf<QuickActionRequest?>(null)
    private var quickActionSerial = 0

    /** True when quick_action_input was delivered from the widget voice mic (needs voice TTS reply). */
    @Deprecated("Folded into QuickActionRequest.isVoice — kept only so KernelNavHost can read it without overload churn")
    private val adbQuickActionIsVoice = mutableStateOf(false)

    /** Bridges ADB `--es slot_reply_input` extras into ActionsViewModel.onSlotReply(). */
    private val adbSlotReplyInput = mutableStateOf<String?>(null)

    private val requestOnboardingPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        adbChatInput.value = intent.getStringExtra("chat_input")
        // Only seed widget extras on a genuine cold start. On process-death restore,
        // savedInstanceState is non-null and NavController restores its back stack;
        // re-seeding here would cause LaunchedEffect to navigate again with a fresh
        // (unconsumed) entry and re-execute the query unexpectedly.
        if (savedInstanceState == null) {
            readQuickActionInput(intent)?.let {
                val voice = intent.getBooleanExtra("quick_action_is_voice", false)
                adbQuickActionInput.value = QuickActionRequest(it, voice, ++quickActionSerial)
                adbQuickActionIsVoice.value = voice
            }
        }
        adbSlotReplyInput.value = intent.getStringExtra("slot_reply_input")
        handleAdbProfileText(intent)
        requestStartupPermissionsIfNeeded()
        setContent {
            val useSystemColorsState = chatPreferences.useSystemColors
                .collectAsState(initial = true)
            KernelAITheme(dynamicColor = useSystemColorsState.value) {
                KernelNavHost(
                    initialChatQuery = adbChatInput.value,
                    initialQuickActionQuery = adbQuickActionInput.value?.query,
                    initialQuickActionIsVoice = adbQuickActionInput.value?.isVoice ?: false,
                    quickActionSerial = adbQuickActionInput.value?.serial ?: 0,
                    initialSlotReply = adbSlotReplyInput.value,
                    favouriteShortcutRepository = favouriteShortcutRepository,
                    recentShortcutTracker = recentShortcutTracker,
                )
            }
        }
    }

    /**
     * Retry starting WakeWordService now that we are in the foreground.
     * Application.onCreate fires the heyJandalEnabled collector before the activity is
     * visible, so startForegroundService() fails with mAllowStartForeground=false there.
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            if (wakeWordPreferences.heyJandalEnabled.first()) {
                WakeWordService.start(this@MainActivity)
            }
        }
    }


    /**
     * Called when AppAuth's PendingIntent delivers the OAuth result back to this activity.
     * With [android:launchMode="singleTop"] and [FLAG_ACTIVITY_SINGLE_TOP], the existing
     * instance receives the callback here rather than being re-created — surviving Samsung's
     * aggressive memory management on Android 16 (S23 Ultra, issue #195).
     *
     * Also handles ADB test `--es chat_input` and `--es profile_text` extras for regression testing.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // #1191: Clear one-shot ADB extras before reading new intent extras.
        // When a new `quick_action_input` arrives without `slot_reply_input`, the stale
        // slot reply value would persist and re-fire the LaunchedEffect in ActionsScreen
        // if the composable re-enters between independent commands, causing the previous
        // case's slot reply to overwrite the newly primed pending slot.
        adbSlotReplyInput.value = null
        intent.getStringExtra("chat_input")?.let { adbChatInput.value = it }
        readQuickActionInput(intent)?.let {
            val voice = intent.getBooleanExtra("quick_action_is_voice", false)
            adbQuickActionInput.value = QuickActionRequest(it, voice, ++quickActionSerial)
            adbQuickActionIsVoice.value = voice
        }
        intent.getStringExtra("slot_reply_input")?.let { adbSlotReplyInput.value = it }
        handleAdbProfileText(intent)
        if (AuthorizationResponse.fromIntent(intent) != null ||
            AuthorizationException.fromIntent(intent) != null) {
            authRepository.deliverAuthResponse(intent)
        }
    }

    private fun readQuickActionInput(intent: Intent): String? {
        intent.getStringExtra("quick_action_input")
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        return intent.getStringExtra("quick_action_input_encoded")
            ?.takeIf { it.isNotBlank() }
            ?.let(Uri::decode)
    }

    /**
     * ADB test hook: `adb shell am start -n ACTIVITY --es profile_text "..."` saves
     * the given text as the user profile and logs the parsed YAML to logcat (tag KernelAI)
     * so device tests can validate extraction quality without manual UI interaction.
     */
    private fun handleAdbProfileText(intent: Intent) {
        intent.getStringExtra("profile_text")?.let { text ->
            // ADB extras can't contain literal newlines; accept \n escape sequences from the test harness
            val normalized = text.replace("\\n", "\n")
            lifecycleScope.launch { userProfileRepository.save(normalized) }
        }
    }


    /**
     * Build the list of runtime permissions that should be requested at startup.
     * Currently only POST_NOTIFICATIONS is included (needed for alarms, timers,
     * reminders, and download progress). Other runtime permissions (Location,
     * Contacts, Calendar, Phone) are requested on first feature use via
     * contextual permission overlays (#1312).
     *
     * Extracted for testability. Package-private to allow unit testing.
     */
    internal fun buildMissingStartupPermissions(context: android.content.Context, sdkInt: Int = Build.VERSION.SDK_INT): List<String> {
        return buildList {
            if (sdkInt >= Build.VERSION_CODES.TIRAMISU &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun requestStartupPermissionsIfNeeded() {
        val prefs = getSharedPreferences(PREFS_RUNTIME_PERMISSIONS, MODE_PRIVATE)
        val forcePromptForTests = intent?.getBooleanExtra("force_permission_prompt", false) == true
        if (!forcePromptForTests && prefs.getBoolean(KEY_ONBOARDING_PERMISSIONS_REQUESTED, false)) return

        val missingPermissions = buildMissingStartupPermissions(this@MainActivity)

        if (missingPermissions.isEmpty()) {
            prefs.edit().putBoolean(KEY_ONBOARDING_PERMISSIONS_REQUESTED, true).apply()
            return
        }

        // Mark before launch so a config change / activity restart doesn't re-trigger a staggered
        // sequence of permission prompts across multiple app opens.
        if (!forcePromptForTests) {
            prefs.edit().putBoolean(KEY_ONBOARDING_PERMISSIONS_REQUESTED, true).apply()
        }
        requestOnboardingPermissions.launch(missingPermissions.toTypedArray())
    }
}
