package com.kernel.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kernel.ai.core.inference.auth.HuggingFaceAuthRepository
import com.kernel.ai.core.memory.repository.UserProfileRepository
import com.kernel.ai.core.ui.theme.KernelAITheme
import com.kernel.ai.navigation.KernelNavHost
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: HuggingFaceAuthRepository

    /** Injected for ADB `--es profile_text` test support — triggers profile parse + logcat output. */
    @Inject lateinit var userProfileRepository: UserProfileRepository

    /** Bridges ADB `--es chat_input` extras (onCreate + onNewIntent) into the nav graph. */
    private val adbChatInput = mutableStateOf<String?>(null)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val requestLocationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    private val requestContactsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        adbChatInput.value = intent.getStringExtra("chat_input")
        handleAdbProfileText(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            requestLocationPermission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }
        setContent {
            KernelAITheme {
                KernelNavHost(initialChatQuery = adbChatInput.value)
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
        intent.getStringExtra("chat_input")?.let { adbChatInput.value = it }
        handleAdbProfileText(intent)
        if (AuthorizationResponse.fromIntent(intent) != null ||
            AuthorizationException.fromIntent(intent) != null) {
            authRepository.deliverAuthResponse(intent)
        }
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
}
