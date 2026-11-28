package com.kernel.ai.alarm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.ui.theme.KernelAITheme

/**
 * Full-screen alert for a ringing alarm or timer (#2185). The alert notification's full-screen intent opens
 * this activity so the alert takes over the screen, including a locked one (`showWhenLocked` and
 * `turnScreenOn` in the manifest). The buttons hand the action to [ClockAlertService], which owns playback
 * and state, then the activity closes once its alert is no longer ringing.
 */
class ClockAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val alert = intent.toTriggeredClockAlert()
        if (alert == null) {
            finish()
            return
        }
        setContent {
            KernelAITheme {
                val ringingOwnerIds by ClockAlertService.activeAlertOwnerIds.collectAsState()
                var wasRinging by remember { mutableStateOf(false) }
                LaunchedEffect(ringingOwnerIds) {
                    if (alert.ownerId in ringingOwnerIds) {
                        wasRinging = true
                    } else if (wasRinging) {
                        finish()
                    }
                }
                ClockAlertScreen(
                    alert = alert,
                    onStop = {
                        startService(clockAlertServiceIntent(this, ClockAlertContract.ACTION_STOP_ALERT, alert))
                        finish()
                    },
                    onSnoozeOrAddMinute = {
                        val action = if (alert.type == ClockEventType.TIMER) {
                            ClockAlertContract.ACTION_ADD_MINUTE_ALERT
                        } else {
                            ClockAlertContract.ACTION_SNOOZE_ALERT
                        }
                        startService(clockAlertServiceIntent(this, action, alert))
                        finish()
                    },
                )
            }
        }
    }

    companion object {
        internal fun createIntent(context: Context, alert: TriggeredClockAlert): Intent =
            Intent(context, ClockAlertActivity::class.java).apply {
                putTriggeredClockAlertExtras(alert)
                putExtra(ClockAlertContract.EXTRA_AUTO_SNOOZE_COUNT, alert.autoSnoozeCount)
            }
    }
}

@Composable
private fun ClockAlertScreen(
    alert: TriggeredClockAlert,
    onStop: () -> Unit,
    onSnoozeOrAddMinute: () -> Unit,
) {
    val isTimer = alert.type == ClockEventType.TIMER
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (isTimer) "Timer" else "Alarm",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = alert.title,
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (alert.label.isNotBlank() && alert.label != alert.title) {
                Text(
                    text = alert.label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(Modifier.height(48.dp))
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(if (isTimer) "Dismiss" else "Stop")
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSnoozeOrAddMinute,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(if (isTimer) "+1 min" else "Snooze")
            }
        }
    }
}
