package net.rokoucha.visiomata.playback.media3

import android.graphics.Bitmap
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import net.rokoucha.visiomata.playback.libaribcaption.AribCaptionNative
import java.lang.ref.Cleaner
import kotlin.math.max

internal const val ARIB_CAPTION_MIME_TYPE = "application/x-arib-caption"

@UnstableApi
internal class AribSubtitleParser(
    fontFilePaths: List<String>,
) : SubtitleParser {
    private class NativeState(
        val handle: Long,
    ) : Runnable {
        override fun run() = AribCaptionNative.destroy(handle)
    }

    private val nativeState = NativeState(AribCaptionNative.create(fontFilePaths.toTypedArray()))

    @Suppress("unused")
    private val cleanable = cleaner.register(this, nativeState)

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val caption = AribCaptionNative.decode(nativeState.handle, data, offset, length) ?: return
        val planeWidth = max(1, caption.planeWidth).toFloat()
        val planeHeight = max(1, caption.planeHeight).toFloat()
        val cues =
            caption.bitmaps.indices.map { index ->
                val width = caption.widths[index]
                val height = caption.heights[index]
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(caption.bitmaps[index], 0, width, 0, 0, width, height)
                Cue
                    .Builder()
                    .setBitmap(bitmap)
                    .setPosition((caption.xs[index] / planeWidth).coerceIn(0f, 1f))
                    .setPositionAnchor(Cue.ANCHOR_TYPE_START)
                    .setLine((caption.ys[index] / planeHeight).coerceIn(0f, 1f), Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_START)
                    .setSize((width / planeWidth).coerceIn(0f, 1f))
                    // ARIB SD caption planes can be 720x480 even when presented as 16:9. Supplying both
                    // logical width and height makes SubtitleView stretch the rendered bitmap to that
                    // non-square display mapping. Let it derive height from the bitmap so glyphs retain
                    // their rendered aspect ratio; position and width still follow the ARIB plane.
                    .build()
            }
        val durationUs =
            if (caption.durationMs == aribDurationIndefinite) C.TIME_UNSET else caption.durationMs * 1_000
        output.accept(CuesWithTiming(cues, C.TIME_UNSET, durationUs))
    }

    override fun reset() = AribCaptionNative.reset(nativeState.handle)

    override fun getCueReplacementBehavior() = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE

    private companion object {
        val cleaner: Cleaner = Cleaner.create()
        const val aribDurationIndefinite = Long.MAX_VALUE
    }
}

@UnstableApi
internal class VisiomataSubtitleParserFactory(
    private val fontFilePaths: List<String>,
) : SubtitleParser.Factory {
    init {
        // Media3 validates a TrackOutput's MIME type before consulting SubtitleParser.Factory.
        MimeTypes.registerCustomMimeType(ARIB_CAPTION_MIME_TYPE, "arib", C.TRACK_TYPE_TEXT)
    }

    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format) =
        format.sampleMimeType == ARIB_CAPTION_MIME_TYPE || delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (format.sampleMimeType == ARIB_CAPTION_MIME_TYPE) {
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        } else {
            delegate.getCueReplacementBehavior(format)
        }

    override fun create(format: Format): SubtitleParser =
        if (format.sampleMimeType == ARIB_CAPTION_MIME_TYPE) {
            AribSubtitleParser(fontFilePaths)
        } else {
            delegate.create(format)
        }
}
