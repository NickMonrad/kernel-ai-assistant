package com.kernel.ai.alarm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kernel.ai.R
import com.kernel.ai.core.memory.clock.ClockEventType
import com.kernel.ai.core.memory.prefs.ChatPreferences
import com.kernel.ai.core.ui.theme.KernelAITheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class RingingActivity : ComponentActivity() {
    @Inject lateinit var chatPreferences: ChatPreferences
    private var alert: TriggeredClockAlert? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        alert = intent.toTriggeredClockAlert()
        val activeAlert = alert ?: run {
            finish()
            return
        }
        setContent {
            val useSystemColors by chatPreferences.useSystemColors.collectAsState(initial = true)
            KernelAITheme(dynamicColor = useSystemColors) {
                var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

                LaunchedEffect(activeAlert) {
                    while (ClockAlertService.isAlertActive(activeAlert)) {
                        delay(1_000L)
                        nowMillis = System.currentTimeMillis()
                    }
                    finish()
                }
                RingingScreen(
                    alert = activeAlert,
                    timeText = ringingTimeText(activeAlert.occurrenceTriggerAtMillis, nowMillis),
                    onStop = { handle(ClockAlertContract.ACTION_STOP_ALERT) },
                    onSnooze = { handle(ClockAlertContract.ACTION_SNOOZE_ALERT) },
                )
            }
        }
    }

    private fun handle(action: String) {
        val activeAlert = alert ?: return
        startService(ringingActionIntent(this, action, activeAlert))
        if (action == ClockAlertContract.ACTION_STOP_ALERT) finish()
    }
}


@Composable
internal fun RingingScreen(
    alert: TriggeredClockAlert,
    timeText: String,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),

            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(
                        modifier = Modifier.size(64.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_launcher_monochrome),
                            contentDescription = "Jandal",
                            modifier = Modifier.padding(14.dp).fillMaxSize(),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = if (alert.type == ClockEventType.TIMER) "Timer" else "Alarm",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = alert.label.ifBlank { alert.title },
                        modifier = Modifier.testTag("ringing_label"),
                        style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = timeText,
                        modifier = Modifier.testTag("ringing_elapsed_time"),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(40.dp))
                    Button(
                        onClick = onStop,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("ringing_stop"),
                    ) {
                        Text("Stop", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(
                        onClick = onSnooze,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("ringing_snooze"),
                    ) {
                        Text("Snooze", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

private fun ringingTimeText(triggerAtMillis: Long?, nowMillis: Long): String {
    if (triggerAtMillis == null) return "Ringing now"
    val time = DateTimeFormatter.ofPattern("h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(triggerAtMillis))
    val elapsedSeconds = ((nowMillis - triggerAtMillis).coerceAtLeast(0L)) / 1_000L
    val elapsed = "%d:%02d".format(elapsedSeconds / 60L, elapsedSeconds % 60L)
    return "Ringing since $time · $elapsed elapsed"
}

