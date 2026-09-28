@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback.media3

import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.container.NalUnitUtil

/**
 * Builds the HEVC track [Format] from VPS/SPS/PPS NAL units carried in video access units.
 *
 * This mirrors Media3's `H265Reader`: the parameter sets are collected from the Annex-B stream,
 * the SPS yields width/height/color via [NalUnitUtil.parseH265SpsNalUnit], and the codec-specific
 * data is the three NAL units concatenated with start codes. The format is produced once; later
 * parameter sets are ignored, since broadcast SPS contents do not change mid-stream.
 *
 * Corrupt parameter sets never throw: [consume] clears the partial set and returns null, so the
 * next access unit retries. Callers must hold back samples until [format] is set, because
 * MediaCodec rejects a format without width, height and codec-specific data.
 */
internal class MmtHevcConfigParser(
    private val formatId: String,
) {
    var format: Format? = null
        private set

    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    /**
     * Scans one Annex-B access unit for VPS/SPS/PPS. Returns the newly built format the first
     * time the set completes, or null when the format is already set or still incomplete.
     */
    fun consume(accessUnit: ByteArray): Format? {
        if (format != null) return null
        forEachNalUnit(accessUnit) { type, offset, limit ->
            when (type) {
                HevcNalType.VPS -> if (vps == null) vps = accessUnit.copyOfRange(offset, limit)
                HevcNalType.SPS -> if (sps == null) sps = accessUnit.copyOfRange(offset, limit)
                HevcNalType.PPS -> if (pps == null) pps = accessUnit.copyOfRange(offset, limit)
            }
        }
        val vps = vps
        val sps = sps
        val pps = pps
        if (vps == null || sps == null || pps == null) return null
        return try {
            buildFormat(vps, sps, pps).also { format = it }
        } catch (error: ParserException) {
            clear()
            null
        } catch (error: RuntimeException) {
            // Truncated SPS syntax overruns the bit reader.
            clear()
            null
        }
    }

    fun reset() {
        format = null
        clear()
    }

    private fun clear() {
        vps = null
        sps = null
        pps = null
    }

    private fun buildFormat(
        vps: ByteArray,
        sps: ByteArray,
        pps: ByteArray,
    ): Format {
        val csd =
            byteArrayOf(0, 0, 1) + vps +
                byteArrayOf(0, 0, 1) + sps +
                byteArrayOf(0, 0, 1) + pps
        val spsData = NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null)
        val tier = spsData.profileTierLevel
        val codecs =
            tier?.let {
                CodecSpecificDataUtil.buildHevcCodecString(
                    it.generalProfileSpace,
                    it.generalTierFlag,
                    it.generalProfileIdc,
                    it.generalProfileCompatibilityFlags,
                    it.constraintBytes,
                    it.generalLevelIdc,
                )
            }
        return Format
            .Builder()
            .setId(formatId)
            .setSampleMimeType(MimeTypes.VIDEO_H265)
            .setCodecs(codecs)
            .setWidth(spsData.width)
            .setHeight(spsData.height)
            .setDecodedWidth(spsData.decodedWidth)
            .setDecodedHeight(spsData.decodedHeight)
            .setColorInfo(
                ColorInfo
                    .Builder()
                    .setColorSpace(spsData.colorSpace)
                    .setColorRange(spsData.colorRange)
                    .setColorTransfer(spsData.colorTransfer)
                    .setLumaBitdepth(spsData.bitDepthLumaMinus8 + 8)
                    .setChromaBitdepth(spsData.bitDepthChromaMinus8 + 8)
                    .build(),
            ).setPixelWidthHeightRatio(spsData.pixelWidthHeightRatio)
            .setMaxNumReorderSamples(spsData.maxNumReorderPics)
            .setMaxSubLayers(spsData.maxSubLayersMinus1 + 1)
            .setInitializationData(listOf(csd))
            .build()
    }
}

/** Invokes [action] with the HEVC NAL type and raw byte range of each NAL unit in [buffer]. */
private inline fun forEachNalUnit(
    buffer: ByteArray,
    action: (type: Int, offset: Int, limit: Int) -> Unit,
) {
    var start = indexOfStartCode(buffer, 0)
    while (start >= 0) {
        val nalStart = start + startCodeLength(buffer, start)
        val next = indexOfStartCode(buffer, nalStart)
        val nalEnd = if (next >= 0) next else buffer.size
        if (nalEnd > nalStart) {
            action(HevcNalType.of(buffer[nalStart].toInt() and 0xff), nalStart, nalEnd)
        }
        if (next < 0) return
        start = next
    }
}

private fun indexOfStartCode(
    buffer: ByteArray,
    from: Int,
): Int {
    var position = from
    while (position + 2 < buffer.size) {
        if (buffer[position].toInt() == 0 && buffer[position + 1].toInt() == 0) {
            if (buffer[position + 2].toInt() == 1) return position
            if (position + 3 < buffer.size && buffer[position + 2].toInt() == 0 && buffer[position + 3].toInt() == 1) {
                return position
            }
        }
        position++
    }
    return -1
}

private fun startCodeLength(
    buffer: ByteArray,
    start: Int,
): Int = if (buffer[start + 2].toInt() == 1) 3 else 4
