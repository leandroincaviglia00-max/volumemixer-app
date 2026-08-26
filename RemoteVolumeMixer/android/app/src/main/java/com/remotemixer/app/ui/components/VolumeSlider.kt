package com.remotemixer.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Big, finger friendly, absolute-position slider (0..100, 1% steps).
 *
 * Why custom instead of the Material Slider:
 *  - a 48dp tall touch target with a small visual track
 *  - tap anywhere to jump, then keep dragging
 *  - separate "dragging" and "settled" behaviour so live updates from the PC
 *    animate smoothly but never fight the finger
 */
@Composable
fun VolumeSlider(
    value: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onDrag: (Int) -> Unit,
    onCommit: (Int) -> Unit,
) {
    var dragging by remember { mutableStateOf(false) }

    val target = (value.coerceIn(0, 100)) / 100f
    val animated by animateFloatAsState(
        targetValue = target,
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 900f),
        label = "vol",
    )
    val fraction = if (dragging) target else animated

    val trackH = 10.dp
    val thumbR = 13.dp

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    val w = size.width.toFloat()
                    val r = thumbR.toPx()
                    fun pct(x: Float): Int {
                        val usable = (w - 2 * r).coerceAtLeast(1f)
                        return (((x - r) / usable).coerceIn(0f, 1f) * 100f).roundToInt()
                    }
                    var last = pct(down.position.x)
                    onDrag(last)
                    drag(down.id) { change ->
                        val p = pct(change.position.x)
                        if (p != last) {          // 1% steps, no redundant events
                            last = p
                            onDrag(p)
                        }
                        change.consume()
                    }
                    dragging = false
                    onCommit(last)
                }
            }
    ) {
        Canvas(Modifier.fillMaxWidth().height(48.dp)) {
            val r = thumbR.toPx()
            val h = trackH.toPx()
            val cy = size.height / 2f
            val left = r
            val right = size.width - r
            val usable = (right - left).coerceAtLeast(1f)
            val x = left + usable * fraction

            // inactive track
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = Offset(left - h / 2, cy - h / 2),
                size = Size(usable + h, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
            )
            // active track
            if (fraction > 0.001f) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(accent.copy(alpha = 0.75f), accent)
                    ),
                    topLeft = Offset(left - h / 2, cy - h / 2),
                    size = Size(usable * fraction + h, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2),
                )
            }
            // thumb halo + thumb
            if (dragging) {
                drawCircle(color = accent.copy(alpha = 0.18f), radius = r * 1.9f, center = Offset(x, cy))
            }
            drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = r, center = Offset(x, cy + 1.5f))
            drawCircle(color = Color.White, radius = r, center = Offset(x, cy))
            drawCircle(color = accent, radius = r * 0.42f, center = Offset(x, cy))
        }
    }
}
