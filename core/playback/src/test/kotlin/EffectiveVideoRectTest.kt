package net.rokoucha.visiomata.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EffectiveVideoRectTest {
    private val rect = VideoRectPx(left = 54, top = 104, width = 539, height = 303)

    @Test
    fun normalPlaybackAppliesBmlRect() {
        assertEquals(rect, effectiveVideoRect(false, rect, false))
    }

    @Test
    fun invisibleDocumentKeepsFullscreenVideo() {
        assertNull(effectiveVideoRect(true, rect, false))
    }

    @Test
    fun missingRectKeepsFullscreenVideo() {
        assertNull(effectiveVideoRect(false, null, false))
    }

    @Test
    fun emptyRectKeepsFullscreenVideo() {
        assertNull(effectiveVideoRect(false, rect.copy(width = 0), false))
        assertNull(effectiveVideoRect(false, rect.copy(height = 0), false))
    }

    @Test
    fun pictureInPictureIgnoresBmlRect() {
        // PiPウィンドウはフルスクリーン用の絶対座標より大幅に小さい。矩形指示を適用すると
        // 映像が窓の外に押し出されるため、無視して全画面表示する。
        assertNull(effectiveVideoRect(false, rect, true))
        assertNull(effectiveVideoRect(true, rect, true))
        assertNull(effectiveVideoRect(false, null, true))
    }
}
