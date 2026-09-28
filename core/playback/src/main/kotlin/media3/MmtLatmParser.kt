@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package net.rokoucha.visiomata.playback.media3

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.AacUtil
import androidx.media3.extractor.TrackOutput

/**
 * Parses the LATM AudioMuxElement carried in one MMT audio MFU (ISO/IEC 14496-3, as profiled by
 * ARIB STD-B60) and returns the raw AAC frame MediaCodec decodes.
 *
 * LATM framing itself is not supported by MediaCodec, so each element is unwrapped exactly like
 * Media3's TS LATM reader does: the in-band StreamMuxConfig yields the track [Format] (emitted
 * once, then re-emitted only when it changes), the PayloadLengthInfo sizes the frame, and the
 * PayloadMux bytes are returned byte-aligned. Only the broadcast profile is accepted (version 0,
 * single program, single layer, `frameLengthType` 0); anything else returns null so the caller
 * drops that sample without disturbing the retained config.
 */
internal class MmtLatmParser(
    private val output: TrackOutput,
    private val formatId: String,
) {
    val framesEmitted: Long
        get() = emittedFrames
    val framesDropped: Long
        get() = droppedFrames

    private var emittedFrames = 0L
    private var droppedFrames = 0L
    private var streamMuxRead = false
    private var audioMuxVersionA = 0
    private var numSubframes = 0
    private var frameLengthType = 0
    private var otherDataPresent = false
    private var otherDataLenBits = 0L
    private var format: Format? = null

    /**
     * Unwraps one MFU's AudioMuxElement into its raw AAC frame, or null when the element cannot be
     * parsed. A null return never throws and never clears a previously parsed config, so a corrupt
     * element only drops its own sample.
     */
    fun parse(element: ByteArray): ByteArray? {
        val parsed = parseElement(element)
        if (parsed == null) {
            droppedFrames++
            return null
        }
        emittedFrames++
        return parsed
    }

    /** Writes one unwrapped AAC frame; every AAC frame is independently decodable. */
    fun writeSample(
        frame: ByteArray,
        timeUs: Long,
    ) {
        output.sampleData(ParsableByteArray(frame), frame.size)
        output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, frame.size, 0, null)
    }

    fun reset() {
        streamMuxRead = false
        audioMuxVersionA = 0
        numSubframes = 0
        frameLengthType = 0
        otherDataPresent = false
        otherDataLenBits = 0L
        format = null
    }

    private fun parseElement(element: ByteArray): ByteArray? {
        if (element.isEmpty()) return null
        val bits = ParsableBitArray(element)
        val useSameStreamMux = bits.readBit()
        if (!useSameStreamMux) {
            if (!parseStreamMuxConfig(element, bits)) return null
            streamMuxRead = true
        } else if (!streamMuxRead) {
            return null
        }
        if (audioMuxVersionA != 0 || numSubframes != 0) return null
        val payloadLength = parsePayloadLengthInfo(bits) ?: return null
        val payload = extractBits(element, bits, payloadLength * 8) ?: return null
        if (otherDataPresent) {
            if (bits.bitsLeft() < otherDataLenBits) return null
            bits.skipBits(otherDataLenBits.toInt())
        }
        return payload
    }

    /**
     * Copies [count] bits at the current position into a byte array (last byte padded with zeros)
     * and advances past them. This deliberately avoids `ParsableBitArray.readBits(ByteArray, …)`,
     * which reads one byte past the end when a byte-aligned read ends exactly at the array end.
     */
    private fun extractBits(
        element: ByteArray,
        bits: ParsableBitArray,
        count: Int,
    ): ByteArray? {
        val position = bits.position
        if (count < 0 || bits.bitsLeft() < count) return null
        val out = ByteArray((count + 7) / 8)
        if (position % 8 == 0 && count % 8 == 0) {
            element.copyInto(out, 0, position / 8, position / 8 + count / 8)
        } else {
            repeat(count) { index ->
                val bitIndex = position + index
                val bit = (element[bitIndex / 8].toInt() shr (7 - bitIndex % 8)) and 1
                if (bit == 1) {
                    out[index / 8] = (out[index / 8].toInt() or (0x80 shr (index % 8))).toByte()
                }
            }
        }
        bits.position = position + count
        return out
    }

    private fun parseStreamMuxConfig(
        element: ByteArray,
        bits: ParsableBitArray,
    ): Boolean {
        if (bits.bitsLeft() < 2) return false
        val audioMuxVersion = bits.readBits(1)
        val versionA = if (audioMuxVersion == 1) bits.readBits(1) else 0
        if (versionA != 0) return false
        if (audioMuxVersion == 1 && latmGetValue(bits) == null) return false
        // sameTimeFraming
        if (bits.bitsLeft() < 14 || !bits.readBit()) return false
        val subframes = bits.readBits(6)
        if (bits.readBits(4) != 0 || bits.readBits(3) != 0) return false
        val nextFormat =
            if (audioMuxVersion == 0) {
                parseVersion0Config(element, bits) ?: return false
            } else {
                if (!parseVersion1Config(bits)) return false
                null
            }
        if (!parseFrameLength(bits) || !parseEpilogue(bits, audioMuxVersion)) return false
        audioMuxVersionA = versionA
        numSubframes = subframes
        if (nextFormat != null && nextFormat != format) {
            format = nextFormat
            output.format(nextFormat)
        }
        return nextFormat != null || format != null
    }

    private fun parseVersion0Config(
        element: ByteArray,
        bits: ParsableBitArray,
    ): Format? {
        val start = bits.position
        val totalBits = start + bits.bitsLeft()
        val parsed: AacUtil.Config
        val configBits: Int
        try {
            parsed = AacUtil.parseAudioSpecificConfig(bits, true)
            configBits = bits.position - start
        } catch (error: ParserException) {
            return null
        } catch (error: RuntimeException) {
            // Truncated configs overrun the bit reader with IllegalStateException.
            return null
        }
        // A truncated config can also leave the position past the end without throwing.
        if (configBits <= 0 || start + configBits > totalBits) return null
        bits.position = start
        val initData = extractBits(element, bits, configBits) ?: return null
        return Format
            .Builder()
            .setId(formatId)
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setCodecs(parsed.codecs)
            .setChannelCount(parsed.channelCount)
            .setSampleRate(parsed.sampleRateHz)
            .setInitializationData(listOf(initData))
            .build()
    }

    private fun parseVersion1Config(bits: ParsableBitArray): Boolean {
        val ascLength = latmGetValue(bits) ?: return false
        val start = bits.position
        try {
            AacUtil.parseAudioSpecificConfig(bits, true)
        } catch (error: ParserException) {
            return false
        } catch (error: RuntimeException) {
            return false
        }
        // fillBits
        val remaining = ascLength.toInt() - (bits.position - start)
        if (remaining < 0 || bits.bitsLeft() < remaining) return false
        bits.skipBits(remaining)
        return true
    }

    private fun parseEpilogue(
        bits: ParsableBitArray,
        audioMuxVersion: Int,
    ): Boolean {
        if (bits.bitsLeft() < 1) return false
        otherDataPresent = bits.readBit()
        otherDataLenBits = 0L
        if (otherDataPresent) {
            otherDataLenBits =
                if (audioMuxVersion == 1) {
                    latmGetValue(bits) ?: return false
                } else {
                    var length = 0L
                    do {
                        if (bits.bitsLeft() < 9) return false
                        val escape = bits.readBit()
                        length = (length shl 8) + bits.readBits(8)
                    } while (escape)
                    length
                }
        }
        if (bits.bitsLeft() < 1) return false
        val crcPresent = bits.readBit()
        if (crcPresent) {
            if (bits.bitsLeft() < 8) return false
            bits.skipBits(8)
        }
        return true
    }

    private fun parseFrameLength(bits: ParsableBitArray): Boolean {
        if (bits.bitsLeft() < 3) return false
        frameLengthType = bits.readBits(3)
        val skip =
            when (frameLengthType) {
                0 -> 8

                // latmBufferFullness
                1 -> 9

                // frameLength
                3, 4, 5 -> 6

                // CELPframeLengthTableIndex
                6, 7 -> 1

                // HVXCframeLengthTableIndex
                else -> return false
            }
        if (bits.bitsLeft() < skip) return false
        bits.skipBits(skip)
        return true
    }

    private fun parsePayloadLengthInfo(bits: ParsableBitArray): Int? {
        if (frameLengthType != 0) return null
        var length = 0
        do {
            if (bits.bitsLeft() < 8) return null
            val value = bits.readBits(8)
            length += value
        } while (value == 255)
        return length
    }

    private fun latmGetValue(bits: ParsableBitArray): Long? {
        if (bits.bitsLeft() < 2) return null
        val bytes = bits.readBits(2)
        var value = 0L
        repeat(bytes + 1) {
            if (bits.bitsLeft() < 8) return null
            value = (value shl 8) + bits.readBits(8)
        }
        return value
    }
}
