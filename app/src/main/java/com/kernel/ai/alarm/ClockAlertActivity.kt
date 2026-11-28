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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.ui.theme.KernelAITheme

/**
 * Full-screen alert for a ringing alarm or timer (#2185). The alert notification's full-screen intent opens this
 * activity over the lock screen (`showWhenLocked` and `turnScreenOn` in the manifest). It shows the newest ringing
 * alert from [ClockAlertService.ringingAlerts], the same alert the notification shows. A second alert or a
 * dismissal updates the screen in place, so `onNewIntent` needs no handling. It closes when nothing is ringing.
 */
class ClockAlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KernelAITheme {
                val ringing by ClockAlertService.ringingAlerts.collectAsState()
                val alert = ringing.lastOrNull()
                LaunchedEffect(alert) {
                    if (alert == null) finish()
                }
                if (alert != null) {
                    ClockAlertScreen(
                        alert = alert,
                        onStop = {
                            startService(clockAlertServiceIntent(this, ClockAlertContract.ACTION_STOP_ALERT, alert))
                        },
                        onSnoozeOrAddMinute = {
                            val action = if (alert.type == ClockEventType.TIMER) {
                                ClockAlertContract.ACTION_ADD_MINUTE_ALERT
                            } else {
                                ClockAlertContract.ACTION_SNOOZE_ALERT
                            }
                            startService(clockAlertServiceIntent(this, action, alert))
                        },
                    )
                }
            }
        }
    }

    companion object {
        internal fun createIntent(context: Context): Intent = Intent(context, ClockAlertActivity::class.java)
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
