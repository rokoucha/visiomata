package net.rokoucha.visiomata.playback.mpeg2toh264

import java.util.concurrent.ConcurrentSkipListMap

internal data class DeinterlaceFrameInfo(
    val interlaced: Boolean,
    val topFieldFirst: Boolean,
) {
    companion object {
        val InterlacedTopFieldFirst =
            DeinterlaceFrameInfo(
                interlaced = true,
                topFieldFirst = true,
            )
        val Progressive =
            DeinterlaceFrameInfo(
                interlaced = false,
                topFieldFirst = false,
            )
    }
}

internal class DeinterlaceMetadataQueue(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val entries = ConcurrentSkipListMap<Long, DeinterlaceFrameInfo>()

    /**
     * Whether the transcoder ever recorded a frame. Non-transcoded paths (TLV/HEVC) never feed
     * the queue, so the shader treats their frames as progressive; `clear` deliberately preserves
     * the latch so reconnects keep the interlaced default on the transcode path.
     */
    @Volatile
    var everRecorded: Boolean = false
        private set

    init {
        require(maxEntries > 0)
    }

    fun record(
        presentationTimeUs: Long,
        info: DeinterlaceFrameInfo,
    ) {
        everRecorded = true
        entries[presentationTimeUs] = info
        while (entries.size > maxEntries) entries.pollFirstEntry()
    }

    fun take(presentationTimeUs: Long): DeinterlaceFrameInfo? {
        val matched = entries.remove(presentationTimeUs) ?: return null
        entries.headMap(presentationTimeUs, false).clear()
        return matched
    }

    fun clear() = entries.clear()

    private companion object {
        const val DEFAULT_MAX_ENTRIES = 32_768
    }
}
