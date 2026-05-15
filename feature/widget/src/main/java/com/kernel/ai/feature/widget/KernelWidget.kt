package com.kernel.ai.feature.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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

@Composable
private fun KernelWidgetContent(packageName: String) {
    GlanceTheme {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .wrapContentHeight()
                .background(
                    DayNightColorProvider(
                        day = Color(0xCCFFFFFF),
                        night = Color(0xCC000000),
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
                            day = Color(0x1A000000),
                            night = Color(0x1AFFFFFF),
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
                            day = Color(0x99000000),
                            night = Color(0x99FFFFFF),
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
                            day = Color(0xFF6750A4),
                            night = Color(0xFFD0BCFF),
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
