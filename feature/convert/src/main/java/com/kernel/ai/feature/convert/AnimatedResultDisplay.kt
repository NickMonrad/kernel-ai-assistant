package com.kernel.ai.feature.convert

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun AnimatedResultDisplay(
    result: ConversionResult?,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = result,
        transitionSpec = {
            (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
        },
        modifier = modifier,
        label = "result_animation",
    ) { currentResult ->
        when (currentResult) {
            null -> {}
            is ConversionResult.Loading -> {
                CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            }
            is ConversionResult.Error -> {
                SuggestionChip(
                    onClick = {},
                    label = { Text(currentResult.message, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            is ConversionResult.Success -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = currentResult.displayValue,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    currentResult.rateInfo?.let { info ->
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
