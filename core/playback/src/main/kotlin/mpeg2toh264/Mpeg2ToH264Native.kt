package net.rokoucha.visiomata.playback.mpeg2toh264

internal object Mpeg2ToH264Native {
    init {
        System.loadLibrary("visiomata_aribcaption")
    }

    external fun create(): Long

    external fun destroy(handle: Long)

    external fun reset(handle: Long)

    external fun push(
        handle: Long,
        data: ByteArray,
        ptsUs: Long,
        hasPts: Boolean,
        finish: Boolean,
    ): ByteArray
}
