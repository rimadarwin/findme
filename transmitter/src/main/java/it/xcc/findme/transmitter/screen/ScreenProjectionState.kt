package it.xcc.findme.transmitter.screen

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenProjectionState {
    UNAVAILABLE,
    STARTING,
    READY,
}

object ScreenProjectionRuntime {
    private val mutableState = MutableStateFlow(ScreenProjectionState.UNAVAILABLE)
    val state: StateFlow<ScreenProjectionState> = mutableState.asStateFlow()

    fun update(state: ScreenProjectionState) {
        mutableState.value = state
    }
}

data class ScreenCaptureSize(
    val width: Int,
    val height: Int,
)

object ScreenCaptureDimensions {
    fun fit(width: Int, height: Int, maxLongEdge: Int = 1280): ScreenCaptureSize {
        require(width > 0 && height > 0)
        val scale = (maxLongEdge.toFloat() / maxOf(width, height)).coerceAtMost(1f)
        return ScreenCaptureSize(
            width = makeEven((width * scale).toInt()),
            height = makeEven((height * scale).toInt()),
        )
    }

    private fun makeEven(value: Int): Int = value.coerceAtLeast(2) and 1.inv()
}
