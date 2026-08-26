package com.remotevolumemixer.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Slider disegnato a mano: grande, arrotondato, con thumb ben visibile,
 * glow durante il trascinamento e valore che cambia mentre il dito si muove.
 * Il tocco imposta subito il valore, senza dover afferrare il thumb.
 */
@Composable
fun VolumeSlider(
    volume: Int,
    enabled: Boolean,
    muted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onVolumeChangeFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf(false) }
    var lastHapticStep by remember { mutableStateOf(-1) }

    val colors = MaterialTheme.colorScheme
    val accent = colors.primary
    val trackColor = colors.onSurface.copy(alpha = if (enabled) 0.12f else 0.07f)
    val fillStart: Color
    val fillEnd: Color
    when {
        !enabled -> {
            fillStart = colors.onSurface.copy(alpha = 0.18f)
            fillEnd = colors.onSurface.copy(alpha = 0.24f)
        }
        muted -> {
            fillStart = colors.onSurfaceVariant.copy(alpha = 0.35f)
            fillEnd = colors.onSurfaceVariant.copy(alpha = 0.5f)
        }
        else -> {
            fillStart = accent.copy(alpha = 0.72f)
            fillEnd = accent
        }
    }

    val thumbColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.35f)
        muted -> colors.onSurfaceVariant
        else -> Color(0xFFF7F9FF)
    }

    val target = (volume.coerceIn(0, 100)) / 100f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 900f),
        label = "volumeFill"
    )
    val fraction = if (dragging) target else animated

    val thumbScale by animateFloatAsState(
        targetValue = if (dragging) 1.22f else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 700f),
        label = "thumbScale"
    )

    val trackHeight = 12.dp
    val thumbRadius = 13.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput

                fun report(x: Float) {
                    val inset = thumbRadius.toPx()
                    val usable = (size.width - inset * 2f).coerceAtLeast(1f)
                    val ratio = ((x - inset) / usable).coerceIn(0f, 1f)
                    val value = (ratio * 100f).roundToInt()
                    onVolumeChange(value)

                    val step = value / 5
                    if (step != lastHapticStep) {
                        lastHapticStep = step
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    lastHapticStep = -1
                    down.consume()
                    report(down.position.x)

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        report(change.position.x)
                    }

                    dragging = false
                    onVolumeChangeFinished()
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = thumbRadius.toPx()
            val centerY = size.height / 2f
            val left = inset
            val right = size.width - inset
            val thumbX = left + (right - left) * fraction

            drawLine(
                color = trackColor,
                start = Offset(left, centerY),
                end = Offset(right, centerY),
                strokeWidth = trackHeight.toPx(),
                cap = StrokeCap.Round
            )

            if (fraction > 0.001f) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(fillStart, fillEnd),
                        startX = left,
                        endX = right
                    ),
                    start = Offset(left, centerY),
                    end = Offset(thumbX, centerY),
                    strokeWidth = trackHeight.toPx(),
                    cap = StrokeCap.Round
                )
            }

            if (dragging && enabled) {
                drawCircle(
                    color = accent.copy(alpha = 0.16f),
                    radius = inset * 2.1f,
                    center = Offset(thumbX, centerY)
                )
            }

            // ombra morbida sotto il thumb
            drawCircle(
                color = Color.Black.copy(alpha = 0.22f),
                radius = inset * thumbScale * 1.02f,
                center = Offset(thumbX, centerY + 1.5.dp.toPx())
            )

            drawCircle(
                color = thumbColor,
                radius = inset * thumbScale,
                center = Offset(thumbX, centerY)
            )

            drawCircle(
                color = if (enabled && !muted) accent else trackColor,
                radius = inset * thumbScale * 0.34f,
                center = Offset(thumbX, centerY)
            )
        }
    }
}
