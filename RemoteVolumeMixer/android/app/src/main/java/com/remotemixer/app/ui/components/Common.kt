package com.remotemixer.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.remotemixer.app.ui.theme.Hairline
import com.remotemixer.app.ui.theme.TextMid

/**
 * The one visual primitive of the app: a rounded, slightly translucent panel
 * with a hairline border and a soft top-light gradient (light glassmorphism).
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    radius: Int = 26,
    glow: Color? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(radius.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.055f),
                        Color.White.copy(alpha = 0.022f),
                    )
                )
            )
            .then(
                if (glow != null) Modifier.background(
                    Brush.verticalGradient(
                        listOf(glow.copy(alpha = 0.10f), Color.Transparent)
                    )
                ) else Modifier
            )
            .border(1.dp, Hairline, shape)
    ) { content() }
}

/** Pulsing status dot: ● Connected / ● Searching / ● Failed */
@Composable
fun StatusDot(color: Color, pulsing: Boolean, size: Int = 9) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha = if (pulsing) {
        transition.animateFloat(
            initialValue = 0.35f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(850), RepeatMode.Reverse),
            label = "dotAlpha",
        ).value
    } else 1f
    Box(
        Modifier
            .size(size.dp)
            .alpha(alpha)
            .clip(RoundedCornerShape(50))
            .background(color)
    )
}

@Composable
fun StatusLine(color: Color, text: String, pulsing: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        StatusDot(color, pulsing)
        Text(
            "  $text",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TextMid,
        modifier = modifier,
    )
}
