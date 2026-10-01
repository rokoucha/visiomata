package net.rokoucha.visiomata.guide

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

class GuideDrawCacheTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun evictionAndClearPreserveImagesReferencedByTheCurrentFrame() {
        val cache = GuideDrawCache(1)
        compose.setContent {
            Canvas(Modifier.size(80.dp, 40.dp).testTag("guide")) {
                val half = size.width / 2
                cache.draw(this, "first", half, size.height, 0f, 0f) { drawRect(Color.Red) }
                cache.draw(this, "second", half, size.height, half, 0f) { drawRect(Color.Blue) }
                cache.clear()
            }
        }
        val pixels = compose.onNodeWithTag("guide").captureToImage().toPixelMap()
        assertEquals(Color.Red, pixels[pixels.width / 4, pixels.height / 2])
        assertEquals(Color.Blue, pixels[pixels.width * 3 / 4, pixels.height / 2])
    }

    @Test
    fun changedContentReplacesOnlyItsOwnCachedTile() {
        val cache = GuideDrawCache(1024 * 1024)
        val density =
            androidx.compose.ui.unit
                .Density(1f)
        val direction = androidx.compose.ui.unit.LayoutDirection.Ltr
        val first = cache.prepare(density, direction, "first", 40f, 40f) { drawRect(Color.Red) }
        val second = cache.prepare(density, direction, "second", 40f, 40f) { drawRect(Color.Blue) }
        val updated = cache.prepare(density, direction, "updated", 40f, 40f) { drawRect(Color.Green) }
        assertNotSame(first, updated)
        assertEquals(android.graphics.Color.GREEN, updated.getPixel(20, 20))
        assertSame(
            second,
            cache.prepare(density, direction, "second", 40f, 40f) { error("Unchanged tile was redrawn") },
        )
    }

    @Test
    fun textCrossingTileBoundaryMatchesWholeImage() {
        val cache = GuideDrawCache(2 * 1024 * 1024)
        compose.setContent {
            Canvas(Modifier.size(160.dp, 80.dp).testTag("guide")) {
                val width = size.width / 2
                val height = size.height / 2
                val paint =
                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.BLACK
                        textSize = 24.dp.toPx()
                    }
                val content: DrawScope.() -> Unit = {
                    drawRect(Color.White)
                    drawIntoCanvas { it.nativeCanvas.drawText("番組Ab", 4.dp.toPx(), height + 8.dp.toPx(), paint) }
                }
                cache.draw(this, "whole", width, size.height, 0f, 0f, content)
                for (tile in 0..1) {
                    cache.draw(this, tile, width, height, width, tile * height) {
                        drawRect(Color.White)
                        translate(top = -tile * height) { content() }
                    }
                }
            }
        }
        val pixels = compose.onNodeWithTag("guide").captureToImage().toPixelMap()
        val half = pixels.width / 2
        for (y in 0 until pixels.height) {
            for (x in 0 until half) {
                assertEquals("pixel ($x, $y)", pixels[x, y], pixels[x + half, y])
            }
        }
    }
}
