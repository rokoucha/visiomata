package net.rokoucha.visiomata.playback.libaribcaption

internal class NativeCaption(
    val durationMs: Long,
    val planeWidth: Int,
    val planeHeight: Int,
    val bitmaps: Array<IntArray>,
    val xs: IntArray,
    val ys: IntArray,
    val widths: IntArray,
    val heights: IntArray,
)

internal object AribCaptionNative {
    init {
        System.loadLibrary("visiomata_aribcaption")
    }

    external fun create(fontFilePaths: Array<String>): Long

    external fun destroy(handle: Long)

    external fun reset(handle: Long)

    external fun decode(
        handle: Long,
        data: ByteArray,
        offset: Int,
        length: Int,
    ): NativeCaption?
}
