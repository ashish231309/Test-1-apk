package com.ashishkumar.nivara.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Shared layout rhythm for screens and cards. Values stay density-independent and respect text scaling. */
object NivaraSpacing {
    val xSmall: Dp = 4.dp
    val small: Dp = 8.dp
    val medium: Dp = 12.dp
    val regular: Dp = 16.dp
    val large: Dp = 24.dp
    val xLarge: Dp = 32.dp
}

/** Durations are short and can be disabled by the platform animator accessibility setting. */
object NivaraMotion {
    const val QUICK_MILLIS = 140
    const val STATE_MILLIS = 190
    const val NAVIGATION_MILLIS = 220

    fun durationMillis(recommendedMillis: Int, animationsEnabled: Boolean): Int =
        if (animationsEnabled) recommendedMillis.coerceAtLeast(0) else 0
}

enum class NivaraStatusTone { INFORMATION, SUCCESS, CAUTION, ERROR }

@Composable
fun NivaraPageHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
        supportingText?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NivaraSectionHeader(
    title: String,
    supportingText: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().semantics { heading() },
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.xSmall),
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        supportingText?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** State remains represented by text and shape as well as color. */
@Composable
fun NivaraStatusCard(
    title: String,
    message: String,
    tone: NivaraStatusTone = NivaraStatusTone.INFORMATION,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val container = when (tone) {
        NivaraStatusTone.INFORMATION -> colors.secondaryContainer
        NivaraStatusTone.SUCCESS -> colors.primaryContainer
        NivaraStatusTone.CAUTION -> colors.tertiaryContainer
        NivaraStatusTone.ERROR -> colors.errorContainer
    }
    val content = when (tone) {
        NivaraStatusTone.INFORMATION -> colors.onSecondaryContainer
        NivaraStatusTone.SUCCESS -> colors.onPrimaryContainer
        NivaraStatusTone.CAUTION -> colors.onTertiaryContainer
        NivaraStatusTone.ERROR -> colors.onErrorContainer
    }
    val duration = NivaraMotion.durationMillis(NivaraMotion.STATE_MILLIS, ValueAnimator.areAnimatorsEnabled())
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(animationSpec = tween(duration)),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(NivaraSpacing.regular),
            verticalArrangement = Arrangement.spacedBy(NivaraSpacing.small),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = content)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = content)
            action?.let { Row(modifier = Modifier.fillMaxWidth()) { it() } }
        }
    }
}

@Composable
fun NivaraSection(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(NivaraSpacing.medium),
        content = { content() },
    )
}
