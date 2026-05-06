package com.javapro.fps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun RealtimeLineChart(
    data: List<Float>,
    label: String,
    color: Color,
    unit: String = "",
    maxValue: Float? = null,
    modifier: Modifier = Modifier
) {
    val max = maxValue ?: (data.maxOrNull()?.coerceAtLeast(1f) ?: 1f)
    val current = data.lastOrNull() ?: 0f

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (unit.isNotEmpty()) "%.1f%s".format(current, unit) else "%.0f".format(current),
                fontSize = 12.sp,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color.copy(alpha = 0.05f))
        ) {
            if (data.size < 2) return@Canvas

            val w = size.width
            val h = size.height
            val step = w / (data.size - 1).coerceAtLeast(1)

            val fillPath = Path()
            val linePath = Path()

            data.forEachIndexed { i, v ->
                val x = i * step
                val y = h - (v / max).coerceIn(0f, 1f) * h * 0.9f - h * 0.05f
                if (i == 0) {
                    fillPath.moveTo(x, h)
                    fillPath.lineTo(x, y)
                    linePath.moveTo(x, y)
                } else {
                    fillPath.lineTo(x, y)
                    linePath.lineTo(x, y)
                }
            }
            fillPath.lineTo(w, h)
            fillPath.close()

            drawPath(fillPath, color = color.copy(alpha = 0.15f))
            drawPath(linePath, color = color, style = Stroke(width = 2.dp.toPx()))

            val lastX = (data.size - 1) * step
            val lastY = h - (current / max).coerceIn(0f, 1f) * h * 0.9f - h * 0.05f
            drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
        }
    }
}
