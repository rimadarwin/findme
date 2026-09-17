package it.xcc.findme.receiver

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SnapshotCaptureAnimation(
    bitmap: Bitmap,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale = remember(bitmap) { Animatable(1f) }
    val translationX = remember(bitmap) { Animatable(0f) }
    val translationY = remember(bitmap) { Animatable(0f) }
    val alpha = remember(bitmap) { Animatable(1f) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetX = with(density) { maxWidth.toPx() * 0.58f }
        val targetY = with(density) { maxHeight.toPx() * 0.58f }
        LaunchedEffect(bitmap) {
            delay(100)
            coroutineScope {
                launch {
                    scale.animateTo(
                        targetValue = 0.08f,
                        animationSpec = tween(650, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    translationX.animateTo(
                        targetValue = targetX,
                        animationSpec = tween(650, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    translationY.animateTo(
                        targetValue = targetY,
                        animationSpec = tween(650, easing = FastOutSlowInEasing),
                    )
                }
                launch {
                    delay(300)
                    alpha.animateTo(0f, animationSpec = tween(350))
                }
            }
            onFinished()
        }
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .align(Alignment.Center)
                .size(width = 220.dp, height = 140.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.translationX = translationX.value
                    this.translationY = translationY.value
                    this.alpha = alpha.value
                    shadowElevation = 14.dp.toPx()
                }
                .border(2.dp, Color.White),
        )
    }
}
