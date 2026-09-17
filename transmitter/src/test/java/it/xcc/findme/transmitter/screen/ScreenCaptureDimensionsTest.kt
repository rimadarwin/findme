package it.xcc.findme.transmitter.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureDimensionsTest {
    @Test
    fun `portrait screen preserves ratio within 1280 pixels`() {
        assertEquals(
            ScreenCaptureSize(width = 720, height = 1280),
            ScreenCaptureDimensions.fit(width = 1080, height = 1920),
        )
    }

    @Test
    fun `landscape and small screens stay even`() {
        assertEquals(
            ScreenCaptureSize(width = 1280, height = 720),
            ScreenCaptureDimensions.fit(width = 1920, height = 1080),
        )
        assertEquals(
            ScreenCaptureSize(width = 718, height = 1278),
            ScreenCaptureDimensions.fit(width = 719, height = 1279),
        )
    }
}
