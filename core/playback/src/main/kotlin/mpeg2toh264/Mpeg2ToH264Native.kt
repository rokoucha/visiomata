package net.rokoucha.visiomata.playback.mpeg2toh264

import java.nio.ByteBuffer

internal object Mpeg2ToH264Native {
    init {
        System.loadLibrary("visiomata_aribcaption")
    }

    external fun create(): Long

    external fun destroy(handle: Long)

    external fun reset(handle: Long)

    /**
     * Returns the native output allocation as a direct buffer without copying. The caller
     * releases it exactly once via [freeDirect]. Null means the bridge itself failed.
     */
    external fun pushDirect(
        handle: Long,
        data: ByteArray,
        ptsUs: Long,
        hasPts: Boolean,
        finish: Boolean,
    ): ByteBuffer?

    external fun freeDirect(buffer: ByteBuffer)
}
