package com.kernel.ai

import android.Manifest
import android.app.ComponentCaller
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
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
import com.kernel.ai.core.permissions.recordPermissionResult
import com.kernel.ai.core.inference.diagnostics.JandalEvents
import androidx.core.app.ActivityCompat
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.navigation.KernelNavHost
import com.kernel.ai.feature.widget.ListsWidgetConfig
import com.kernel.ai.assistant.WakeWordService
import com.kernel.ai.core.voice.WakeWordPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject
import dagger.hilt.android.AndroidEntryPoint
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var authRepository: HuggingFaceAuthRepository

    /** Injected for ADB `--es profile_text` test support — triggers profile parse + logcat output. */
    @Inject lateinit var wakeWordPreferences: WakeWordPreferences

    @Inject lateinit var userProfileRepository: UserProfileRepository

    @Inject lateinit var clockRepository: com.kernel.ai.core.memory.clock.ClockRepository
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
    /** Bridges launcher shortcut / widget deep-link into the nav graph via the "navigation_route" extra.
     *  Wrapped in [NavRouteRequest] so every delivery — even of the same route string — produces a
     *  distinct observable value and re-fires KernelNavHost's LaunchedEffect. */
    private data class NavRouteRequest(val route: String, val serial: Int)
    private val adbNavigationRoute = mutableStateOf<NavRouteRequest?>(null)

    /**
     * #1604: prompt text from an untrusted caller, shown in the existing input for review. It is
     * never submitted by the app; [serial] re-fires delivery for repeated identical text.
     */
    private data class ExternalDraftRequest(
        val chat: String?,
        val quickAction: String?,
        val slotReply: String?,
        val serial: Int,
    )
    private val externalDraft = mutableStateOf<ExternalDraftRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (shouldResolvePromptExtrasOnActivityCreate(savedInstanceState != null)) {
            val delivery = resolvePromptExtras(intent, launchedFromUid)
            adbChatInput.value = delivery.chatQuery
            delivery.quickActionQuery?.let {
                adbQuickActionInput.value = QuickActionRequest(it, delivery.quickActionIsVoice, ++quickActionSerial)
                adbQuickActionIsVoice.value = delivery.quickActionIsVoice
            }
            intent.getStringExtra("navigation_route")?.takeIf { it.isNotBlank() }?.let { route ->
                val prev = adbNavigationRoute.value
                adbNavigationRoute.value = NavRouteRequest(route, (prev?.serial ?: 0) + 1)
            }
            adbSlotReplyInput.value = delivery.slotReply
            publishExternalDraft(delivery)
            handleAdbProfileText(delivery.profileText)
        }
        setContent {
            val useSystemColorsState = chatPreferences.useSystemColors
                .collectAsState(initial = true)
            KernelAITheme(dynamicColor = useSystemColorsState.value) {
                KernelNavHost(
                    initialChatQuery = adbChatInput.value,
                    initialQuickActionQuery = adbQuickActionInput.value?.query,
                    initialQuickActionIsVoice = adbQuickActionInput.value?.isVoice ?: false,
                    quickActionSerial = adbQuickActionInput.value?.serial ?: 0,
                    initialNavigationRoute = adbNavigationRoute.value?.route,
                    navigationRouteSerial = adbNavigationRoute.value?.serial ?: 0,
                    initialSlotReply = adbSlotReplyInput.value,
                    externalChatDraft = externalDraft.value?.chat,
                    externalQuickActionDraft = externalDraft.value?.quickAction,
                    externalSlotReplyDraft = externalDraft.value?.slotReply,
                    externalDraftSerial = externalDraft.value?.serial ?: 0,
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
            // Exact-alarm access can be revoked or granted in system settings while Jandal is
            // backgrounded. Re-evaluate and replace any OS-cancelled schedules on return.
            clockRepository.restoreScheduledEntries()
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
     * [caller] identifies who re-delivered this intent (#1604): the original launcher's identity
     * must not vouch for a later intent from another app.
     */
    override fun onNewIntent(intent: Intent, caller: ComponentCaller) {
        super.onNewIntent(intent, caller)
        setIntent(intent)
        val delivery = resolvePromptExtras(intent, caller.uid)
        // #1191: Clear one-shot ADB extras before reading new intent extras.
        // When a new `quick_action_input` arrives without `slot_reply_input`, the stale
        // slot reply value would persist and re-fire the LaunchedEffect in ActionsScreen
        // if the composable re-enters between independent commands, causing the previous
        // case's slot reply to overwrite the newly primed pending slot.
        adbSlotReplyInput.value = null
        intent.getStringExtra("navigation_route")?.takeIf { it.isNotBlank() }?.let { route ->
            val prev = adbNavigationRoute.value
            adbNavigationRoute.value = NavRouteRequest(route, (prev?.serial ?: 0) + 1)
        }
        delivery.chatQuery?.let { adbChatInput.value = it }
        delivery.quickActionQuery?.let {
            adbQuickActionInput.value = QuickActionRequest(it, delivery.quickActionIsVoice, ++quickActionSerial)
            adbQuickActionIsVoice.value = delivery.quickActionIsVoice
        }
        delivery.slotReply?.let { adbSlotReplyInput.value = it }
        publishExternalDraft(delivery)
        handleAdbProfileText(delivery.profileText)
        if (AuthorizationResponse.fromIntent(intent) != null ||
            AuthorizationException.fromIntent(intent) != null) {
            authRepository.deliverAuthResponse(intent)
        }
    }

    /** Applies the #1604 trust boundary to this intent's prompt extras. */
    private fun resolvePromptExtras(intent: Intent, callerUid: Int): ExternalPromptDelivery {
        val trust = classifyExternalPromptCaller(
            callerUid = callerUid,
            ownUid = Process.myUid(),
            isDebugBuild = BuildConfig.DEBUG,
            harnessTokenValid = BuildConfig.DEBUG && debugHarnessTokenValid(intent),
        )
        val delivery = resolveExternalPromptDelivery(
            chatInput = intent.getStringExtra("chat_input"),
            quickActionInput = intent.getStringExtra("quick_action_input"),
            quickActionInputEncoded = intent.getStringExtra("quick_action_input_encoded"),
            quickActionIsVoice = intent.getBooleanExtra("quick_action_is_voice", false),
            slotReplyInput = intent.getStringExtra("slot_reply_input"),
            profileText = intent.getStringExtra("profile_text"),
            trust = trust,
            isDebugBuild = BuildConfig.DEBUG,
            decodeUriComponent = { Uri.decode(it) },
        )
        if (delivery != ExternalPromptDelivery()) {
            // Caller identity and decision only; prompt text is never logged here.
            Log.i("KernelAI", "external_prompt_trust callerUid=$callerUid trust=$trust")
        }
        return delivery
    }

    /** Debug builds only: does the intent carry the token the adb harness wrote via `run-as`? */
    private fun debugHarnessTokenValid(intent: Intent): Boolean {
        val presented = intent.getStringExtra(ADB_HARNESS_TOKEN_EXTRA) ?: return false
        val stored = runCatching { java.io.File(filesDir, ADB_HARNESS_TOKEN_FILE).readText().trim() }.getOrNull()
        return harnessTokenMatches(presented, stored)
    }

    private fun publishExternalDraft(delivery: ExternalPromptDelivery) {
        if (delivery.chatDraft == null && delivery.quickActionDraft == null && delivery.slotReplyDraft == null) return
        externalDraft.value = ExternalDraftRequest(
            chat = delivery.chatDraft,
            quickAction = delivery.quickActionDraft,
            slotReply = delivery.slotReplyDraft,
            serial = (externalDraft.value?.serial ?: 0) + 1,
        )
    }

    /**
     * Debug-only ADB test hook (`adb shell am start -n ACTIVITY --es profile_text "..."`): saves the
     * given text as the user profile and logs the parsed YAML to logcat (tag KernelAI) so device
     * tests can validate extraction quality. [ExternalPromptDelivery.profileText] is null in release
     * builds and for untrusted callers (#1604).
     */
    private fun handleAdbProfileText(text: String?) {
        text ?: return
        // ADB extras can't contain literal newlines; accept \n escape sequences from the test harness
        val normalized = text.replace("\\n", "\n")
        lifecycleScope.launch { userProfileRepository.save(normalized) }
    }



}
