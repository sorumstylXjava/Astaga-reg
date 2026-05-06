package com.javapro.fps.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact FPS bubble — for overlay or floating window use.
 * Embed in a FloatingWindowService or draggable overlay.
 *
 * Usage:
 *   FpsBubble(fps = 119.8f, refreshRateHz = 120f)
 */
@Composable
fun FpsBubble(
    fps: Float,
    refreshRateHz: Float = 60f,
    frameTimeMs: Float = 0f,
    showFrameTime: Boolean = true
) {
    val fpsColor = when {
        fps >= refreshRateHz * 0.9f -> Color(0xFF4CAF50)
        fps >= refreshRateHz * 0.5f -> Color(0xFFFFCA28)
        fps > 0f                    -> Color(0xFFEF5350)
        else                        -> Color(0xFF78909C)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dot")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dotAlpha"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC000000))
            .border(0.8.dp, fpsColor.copy(0.4f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(fpsColor.copy(dotAlpha))
                )
                Text(
                    text = if (fps > 0f) "%.0f".format(fps) else "--",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = fpsColor
                )
                Text(
                    "FPS",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = fpsColor.copy(0.7f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (showFrameTime && frameTimeMs > 0f) {
                Text(
                    "%.1f ms".format(frameTimeMs),
                    fontSize = 9.sp,
                    color = Color.White.copy(0.5f)
                )
            }
        }
    }
}
