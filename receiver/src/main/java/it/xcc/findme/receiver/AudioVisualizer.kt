package it.xcc.findme.receiver

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun AudioVisualizer(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedLevel by animateFloatAsState(
        targetValue = if (active) level.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "audioLevel",
    )
    val waveColor = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceVariant

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(150.dp)
            .background(background, RoundedCornerShape(16.dp)),
    ) {
        val bars = 31
        val gap = size.width / (bars * 1.8f)
        val stroke = gap * 0.75f
        val centerY = size.height / 2f
        repeat(bars) { index ->
            val phase = index.toFloat() / (bars - 1)
            val envelope = sin(Math.PI * phase).toFloat().coerceAtLeast(0.15f)
            val variation = 0.55f + 0.45f * sin(index * 1.7f).coerceAtLeast(0f)
            val height = size.height * (0.08f + animatedLevel * envelope * variation * 0.82f)
            val x = gap + index * gap * 1.75f
            drawLine(
                color = waveColor,
                start = androidx.compose.ui.geometry.Offset(x, centerY - height / 2f),
                end = androidx.compose.ui.geometry.Offset(x, centerY + height / 2f),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}
