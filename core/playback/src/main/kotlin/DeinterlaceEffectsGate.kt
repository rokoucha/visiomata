@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback

import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput

/** Live stream containers distinguished by extractor sniffing. */
internal enum class StreamKind {
    TLV,
    TS,
}

/**
 * Latches the first sniffed container of a live stream and decides whether the player must
 * restart with the deinterlace video effects.
 *
 * The renderer only builds its GL pipeline when effects are set before prepare(), but the
 * container is known only after extractor sniffing, so the engine prepares without effects and
 * restarts once when a TS stream is detected. Fresh extractors sniff again on every reconnect,
 * but the container never changes for one URL, so later reports are ignored. HEVC therefore
 * always plays through the direct output path.
 */
internal class DeinterlaceEffectsGate(
    private val effectsAvailable: Boolean,
) {
    var latchedKind: StreamKind? = null
        private set

    /** Latches the first kind seen; returns whether the player must restart with effects. */
    fun onSniffed(kind: StreamKind): Boolean {
        if (latchedKind != null) return false
        latchedKind = kind
        return effectsAvailable && kind == StreamKind.TS
    }
}

/** Reports [kind] to [onSniffed] when [delegate] accepts the stream; forwards everything else. */
internal class SniffReportingExtractor(
    private val delegate: Extractor,
    private val kind: StreamKind,
    private val onSniffed: (StreamKind) -> Unit,
) : Extractor by delegate {
    override fun sniff(input: ExtractorInput): Boolean =
        delegate.sniff(input).also { matched ->
            if (matched) onSniffed(kind)
        }
}
