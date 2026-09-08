@file:Suppress("ktlint:standard:max-line-length")

package net.rokoucha.visiomata.playback.media3

import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.ElementaryStreamReader
import androidx.media3.extractor.ts.PesReader
import androidx.media3.extractor.ts.SectionPayloadReader
import androidx.media3.extractor.ts.SectionReader
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.ts.TsPayloadReader
import net.rokoucha.visiomata.playback.AudioComponentState
import net.rokoucha.visiomata.playback.bml.BmlTsDemuxer
import net.rokoucha.visiomata.playback.mpeg2toh264.DeinterlaceMetadataQueue
import net.rokoucha.visiomata.playback.mpeg2toh264.TranscodingH262Reader
import kotlin.math.min

@UnstableApi
internal class AribTsPayloadReaderFactory(
    private val transcodeMpeg2Video: Boolean = false,
    private val deinterlaceMetadata: DeinterlaceMetadataQueue? = null,
    private val bmlDemuxer: BmlTsDemuxer? = null,
    private val audioComponentState: AudioComponentState = AudioComponentState(emptyList()),
    private val streamGeneration: Long = 0,
) : TsPayloadReader.Factory {
    private val delegate = DefaultTsPayloadReaderFactory()

    override fun createInitialPayloadReaders(): SparseArray<TsPayloadReader> = delegate.createInitialPayloadReaders()

    override fun createPayloadReader(
        streamType: Int,
        esInfo: TsPayloadReader.EsInfo,
    ): TsPayloadReader? {
        val component = esInfo.aribComponentInfo()
        var componentTrackId: (() -> Int)? = null
        // TsExtractor replaces PMT stream_type 0x06 with EsInfo.streamType before calling us.
        // ARIB's data_component_descriptor isn't one of Media3's built-in mappings, so that value is
        // C.INDEX_UNSET (-1). The descriptor itself is therefore the authoritative identification.
        val reader =
            if (streamType == dataCarouselStreamType && component?.componentId != null && bmlDemuxer != null) {
                SectionReader(BmlSectionReader(bmlDemuxer, streamType, component))
            } else if (transcodeMpeg2Video && streamType == TsExtractor.TS_STREAM_TYPE_H262) {
                PesReader(TranscodingH262Reader(checkNotNull(deinterlaceMetadata)))
            } else if (streamType == TsExtractor.TS_STREAM_TYPE_AAC_ADTS) {
                val aacReader =
                    AribAacReader(
                        componentTag = component?.componentId,
                        descriptorMainLanguage = component?.languages?.getOrNull(0) ?: esInfo.language,
                        descriptorSubLanguage = component?.languages?.getOrNull(1),
                        roleFlags = esInfo.roleFlags,
                        descriptorIsMainComponent = component?.isMainAudio,
                        audioComponentState = audioComponentState,
                        streamGeneration = streamGeneration,
                    )
                componentTrackId = { aacReader.mainTrackId }
                PesReader(aacReader)
            } else if (esInfo.hasAribCaptionDescriptor()) {
                AribCaptionPesReader(esInfo.language)
            } else {
                delegate.createPayloadReader(streamType, esInfo)
            }
        return if (reader != null && component?.componentId != null && reader !is SectionReader && bmlDemuxer != null) {
            ComponentTrackingReader(reader, bmlDemuxer, streamType, component, componentTrackId)
        } else {
            reader
        }
    }

    internal data class AribComponentInfo(
        val componentId: Int?,
        val dataComponentId: Int?,
        val additionalInfo: ByteArray?,
        val audioMode: Int?,
        val isMainAudio: Boolean?,
        val languages: List<String>,
    )

    private fun TsPayloadReader.EsInfo.aribComponentInfo(): AribComponentInfo? {
        val data = ParsableByteArray(descriptorBytes)
        var componentId: Int? = null
        var dataComponentId: Int? = null
        var additionalInfo: ByteArray? = null
        var audioMode: Int? = null
        var isMainAudio: Boolean? = null
        var languages = emptyList<String>()
        while (data.bytesLeft() >= 2) {
            val tag = data.readUnsignedByte()
            val length = data.readUnsignedByte()
            if (length > data.bytesLeft()) break
            val end = data.position + length
            if (tag == streamIdentifierDescriptorTag && length >= 1) componentId = data.readUnsignedByte()
            if (tag == dataComponentDescriptorTag && length >= 2) {
                dataComponentId = data.readUnsignedShort()
                additionalInfo = descriptorBytes.copyOfRange(data.position, end)
            }
            if (tag == audioComponentDescriptorTag && length >= minimumAudioComponentLength) {
                val body = descriptorBytes.copyOfRange(data.position, end)
                audioMode = body[1].toInt() and audioModeMask
                val multilingual = body[5].toInt() and 0x80 != 0
                isMainAudio = body[5].toInt() and 0x40 != 0
                languages =
                    buildList {
                        languageCode(body, 6)?.let(::add)
                        if (multilingual) languageCode(body, 9)?.let(::add)
                    }
            }
            data.position = end
        }
        return if (componentId != null || audioMode != null) {
            AribComponentInfo(
                componentId,
                dataComponentId,
                additionalInfo,
                audioMode,
                isMainAudio,
                languages,
            )
        } else {
            null
        }
    }

    private fun languageCode(
        data: ByteArray,
        offset: Int,
    ): String? {
        if (offset + 3 > data.size) return null
        val code = data.copyOfRange(offset, offset + 3)
        return code
            .takeIf { bytes ->
                bytes.all {
                    (it.toInt() and 0xFF) in 'A'.code..'Z'.code ||
                        (it.toInt() and 0xFF) in 'a'.code..'z'.code
                }
            }?.toString(Charsets.US_ASCII)
            ?.lowercase()
    }

    private fun TsPayloadReader.EsInfo.hasAribCaptionDescriptor(): Boolean {
        val data = ParsableByteArray(descriptorBytes)
        while (data.bytesLeft() >= 2) {
            val tag = data.readUnsignedByte()
            val length = data.readUnsignedByte()
            if (length > data.bytesLeft()) return false
            val end = data.position + length
            if (tag == dataComponentDescriptorTag && length >= 2 &&
                data.readUnsignedShort() == captionDataComponentId
            ) {
                return true
            }
            data.position = end
        }
        return false
    }

    private companion object {
        const val dataComponentDescriptorTag = 0xFD
        const val streamIdentifierDescriptorTag = 0x52
        const val audioComponentDescriptorTag = 0xC4
        const val captionDataComponentId = 0x0008
        const val dataCarouselStreamType = 0x0D
        const val minimumAudioComponentLength = 9
        const val audioModeMask = 0x1F
    }
}

@UnstableApi
private class BmlSectionReader(
    private val demuxer: BmlTsDemuxer,
    private val streamType: Int,
    private val component: AribTsPayloadReaderFactory.AribComponentInfo,
) : SectionPayloadReader {
    private var pid = C.INDEX_UNSET

    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        idGenerator.generateNewId()
        pid = idGenerator.trackId
        demuxer.registerComponent(
            pid,
            component.componentId ?: return,
            streamType,
            component.dataComponentId,
            component.additionalInfo,
            idGenerator.formatId.substringBefore('/').toIntOrNull(),
        )
    }

    override fun consume(sectionData: ParsableByteArray) {
        if (demuxer.shouldConsumeSection(pid, sectionData.data, sectionData.position, sectionData.bytesLeft())) {
            demuxer.pushSection(pid, sectionData.data, sectionData.position, sectionData.bytesLeft())
        }
        sectionData.skipBytes(sectionData.bytesLeft())
    }
}

@UnstableApi
private class ComponentTrackingReader(
    private val delegate: TsPayloadReader,
    private val demuxer: BmlTsDemuxer,
    private val streamType: Int,
    private val component: AribTsPayloadReaderFactory.AribComponentInfo,
    private val componentTrackId: (() -> Int)? = null,
) : TsPayloadReader by delegate {
    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        delegate.init(timestampAdjuster, extractorOutput, idGenerator)
        demuxer.registerComponent(
            componentTrackId?.invoke()?.takeUnless { it == C.INDEX_UNSET } ?: idGenerator.trackId,
            component.componentId ?: return,
            streamType,
            component.dataComponentId,
            component.additionalInfo,
            idGenerator.formatId.substringBefore('/').toIntOrNull(),
        )
    }
}

@UnstableApi
private class AribCaptionPesReader(
    private val language: String?,
) : TsPayloadReader {
    private val scratch = ParsableBitArray(ByteArray(scratchSize))
    private var state = stateFindingHeader
    private var bytesRead = 0
    private var extendedHeaderLength = 0
    private var payloadSize = C.LENGTH_UNSET
    private var sampleSize = 0
    private var ptsFlag = false
    private var dtsFlag = false
    private var seenFirstDts = false
    private var timeUs = C.TIME_UNSET
    private lateinit var timestampAdjuster: TimestampAdjuster
    private lateinit var output: TrackOutput

    override fun init(
        timestampAdjuster: TimestampAdjuster,
        extractorOutput: ExtractorOutput,
        idGenerator: TsPayloadReader.TrackIdGenerator,
    ) {
        this.timestampAdjuster = timestampAdjuster
        idGenerator.generateNewId()
        output = extractorOutput.track(idGenerator.trackId, C.TRACK_TYPE_TEXT)
        output.format(
            Format
                .Builder()
                .setId(idGenerator.formatId)
                .setSampleMimeType(ARIB_CAPTION_MIME_TYPE)
                .setContainerMimeType(MimeTypes.VIDEO_MP2T)
                .setLanguage(language ?: "jpn")
                .setRoleFlags(C.ROLE_FLAG_CAPTION)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
                .setSubsampleOffsetUs(Format.OFFSET_SAMPLE_RELATIVE)
                .build(),
        )
    }

    override fun seek() {
        state = stateFindingHeader
        bytesRead = 0
        seenFirstDts = false
        sampleSize = 0
    }

    override fun consume(
        data: ParsableByteArray,
        flags: Int,
    ) {
        if ((flags and TsPayloadReader.FLAG_PAYLOAD_UNIT_START_INDICATOR) != 0) {
            if (state == stateReadingBody) finishSample()
            setState(stateReadingHeader)
        }
        while (data.bytesLeft() > 0) {
            when (state) {
                stateFindingHeader -> {
                    data.skipBytes(data.bytesLeft())
                }

                stateReadingHeader -> {
                    if (continueRead(data, scratch.data, headerSize)) {
                        setState(if (parseHeader()) stateReadingHeaderExtension else stateFindingHeader)
                    }
                }

                stateReadingHeaderExtension -> {
                    val interestingLength = min(maxHeaderExtensionSize, extendedHeaderLength)
                    if (
                        continueRead(data, scratch.data, interestingLength) &&
                        continueRead(data, null, extendedHeaderLength)
                    ) {
                        parseHeaderExtension()
                        sampleSize = 0
                        setState(stateReadingBody)
                    }
                }

                stateReadingBody -> {
                    var readLength = data.bytesLeft()
                    if (payloadSize != C.LENGTH_UNSET) readLength = min(readLength, payloadSize)
                    val oldLimit = data.limit()
                    data.setLimit(data.position + readLength)
                    output.sampleData(data, readLength)
                    data.setLimit(oldLimit)
                    sampleSize += readLength
                    if (payloadSize != C.LENGTH_UNSET) {
                        payloadSize -= readLength
                        if (payloadSize == 0) {
                            finishSample()
                            setState(stateReadingHeader)
                        }
                    }
                }
            }
        }
    }

    private fun finishSample() {
        if (sampleSize > 0 && timeUs != C.TIME_UNSET) {
            output.sampleMetadata(timeUs, C.BUFFER_FLAG_KEY_FRAME, sampleSize, 0, null)
        }
        sampleSize = 0
    }

    private fun setState(newState: Int) {
        state = newState
        bytesRead = 0
    }

    private fun continueRead(
        source: ParsableByteArray,
        target: ByteArray?,
        targetLength: Int,
    ): Boolean {
        val count = min(source.bytesLeft(), targetLength - bytesRead)
        if (count > 0) {
            if (target == null) source.skipBytes(count) else source.readBytes(target, bytesRead, count)
            bytesRead += count
        }
        return bytesRead == targetLength
    }

    private fun parseHeader(): Boolean {
        scratch.position = 0
        if (scratch.readBits(24) != 0x000001) {
            payloadSize = C.LENGTH_UNSET
            return false
        }
        scratch.skipBits(8)
        val packetLength = scratch.readBits(16)
        scratch.skipBits(8)
        ptsFlag = scratch.readBit()
        dtsFlag = scratch.readBit()
        scratch.skipBits(6)
        extendedHeaderLength = scratch.readBits(8)
        payloadSize =
            if (packetLength == 0) {
                C.LENGTH_UNSET
            } else {
                packetLength + 6 - headerSize - extendedHeaderLength
            }
        if (payloadSize < 0) payloadSize = C.LENGTH_UNSET
        return true
    }

    private fun parseHeaderExtension() {
        scratch.position = 0
        timeUs = C.TIME_UNSET
        if (!ptsFlag) return
        scratch.skipBits(4)
        var pts = scratch.readBits(3).toLong() shl 30
        scratch.skipBits(1)
        pts = pts or (scratch.readBits(15).toLong() shl 15)
        scratch.skipBits(1)
        pts = pts or scratch.readBits(15).toLong()
        scratch.skipBits(1)
        if (!seenFirstDts && dtsFlag) {
            scratch.skipBits(4)
            var dts = scratch.readBits(3).toLong() shl 30
            scratch.skipBits(1)
            dts = dts or (scratch.readBits(15).toLong() shl 15)
            scratch.skipBits(1)
            dts = dts or scratch.readBits(15).toLong()
            timestampAdjuster.adjustTsTimestamp(dts)
            seenFirstDts = true
        }
        timeUs = timestampAdjuster.adjustTsTimestamp(pts)
    }

    private companion object {
        const val stateFindingHeader = 0
        const val stateReadingHeader = 1
        const val stateReadingHeaderExtension = 2
        const val stateReadingBody = 3
        const val headerSize = 9
        const val maxHeaderExtensionSize = 10
        const val scratchSize = 10
    }
}
