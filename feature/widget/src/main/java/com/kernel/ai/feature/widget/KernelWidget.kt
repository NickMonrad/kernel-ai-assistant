package com.kernel.ai.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.kernel.ai.core.ui.theme.CharcoalDark
import com.kernel.ai.core.ui.theme.FernGreen
import com.kernel.ai.core.ui.theme.FernGreenLight
import com.kernel.ai.core.ui.theme.SandLight
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.DayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.layout.wrapContentHeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

class KernelWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            KernelWidgetContent(context.packageName)
        }
    }
}

@Suppress("RestrictedApi")
@Composable
private fun KernelWidgetContent(packageName: String) {
    GlanceTheme {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(
                    DayNightColorProvider(
                        day = SandLight.copy(alpha = 0.85f),
                        night = CharcoalDark.copy(alpha = 0.85f),
                    )
                )
                .cornerRadius(24.dp)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .background(
                        DayNightColorProvider(
                            day = FernGreen.copy(alpha = 0.10f),
                            night = FernGreenLight.copy(alpha = 0.10f),
                        )
                    )
                    .cornerRadius(16.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .clickable(actionStartActivity(WidgetTextInputActivity::class.java)),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Ask Jandal…",
                    style = TextStyle(
                        color = DayNightColorProvider(
                            day = CharcoalDark.copy(alpha = 0.87f),
                            night = SandLight.copy(alpha = 0.87f),
                        )
                    ),
                )
            }
            Spacer(GlanceModifier.width(8.dp))
            Box(
                modifier = GlanceModifier
                    .size(48.dp)
                    .background(
                        DayNightColorProvider(
                            day = FernGreen,
                            night = FernGreenLight,
                        )
                    )
                    .cornerRadius(24.dp)
                    .clickable(actionStartActivity(VoiceCommandActivity::class.java)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    provider = ImageProvider(android.R.drawable.ic_btn_speak_now),
                    contentDescription = "Voice input",
                    modifier = GlanceModifier.size(24.dp),
                )
            }
        }
    }
}

class KernelWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = KernelWidget()
}
