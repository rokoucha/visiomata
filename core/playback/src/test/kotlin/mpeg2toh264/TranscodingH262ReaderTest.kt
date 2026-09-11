package net.rokoucha.visiomata.playback.mpeg2toh264

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.TsPayloadReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@UnstableApi
class TranscodingH262ReaderTest {
    private data class Sample(
        val timeUs: Long,
        val flags: Int,
        val size: Int,
    )

    private class RecordingTrackOutput : TrackOutput {
        val formats = Collections.synchronizedList(mutableListOf<Format>())
        val samples = Collections.synchronizedList(mutableListOf<Sample>())
        private var pendingBytes = 0

        @Synchronized
        fun storedButUncommittedBytes(): Int = pendingBytes

        override fun format(format: Format) {
            formats.add(format)
        }

        @Synchronized
        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
        ) {
            data.skipBytes(length)
            pendingBytes += length
        }

        @Synchronized
        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            offset: Int,
        ) {
            data.skipBytes(length)
            pendingBytes += length
        }

        @Synchronized
        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
        ): Int = consumeFrom(input, length)

        @Synchronized
        override fun sampleData(
            input: androidx.media3.common.DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int = consumeFrom(input, length)

        // Mirrors SampleDataQueue: a single call stores at most one chunk, so production
        // must loop until the full sample is written.
        private fun consumeFrom(
            input: androidx.media3.common.DataReader,
            length: Int,
        ): Int {
            val chunk = ByteArray(minOf(length, SINGLE_READ_BYTES))
            val count = input.read(chunk, 0, chunk.size)
            if (count < 0) return count
            pendingBytes += count
            return count
        }

        private companion object {
            const val SINGLE_READ_BYTES = 1_024
        }

        @Synchronized
        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            pendingBytes -= size
            samples.add(Sample(timeUs, flags, size))
        }
    }

    private class FakeExtractorOutput(
        private val trackOutput: TrackOutput,
    ) : ExtractorOutput {
        override fun track(
            id: Int,
            type: Int,
        ): TrackOutput = trackOutput

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class FakeTranscoder(
        private val gate: CountDownLatch = CountDownLatch(0),
        private val failure: RuntimeException? = null,
    ) : H262Transcoder {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val pushAttempts =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val releasedOutputs =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        private val pushCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        /** Set before the worker starts; sizes every emitted unit. */
        var payloadSize = 5

        /** When non-empty, each push consumes the head instead of a zero-filled payload. */
        val queuedPayloads = Collections.synchronizedList(mutableListOf<ByteArray>())

        override fun push(
            data: ByteArray,
            ptsUs: Long,
            hasPts: Boolean,
            finish: Boolean,
        ): ByteBuffer {
            pushAttempts.incrementAndGet()
            check(gate.await(10, TimeUnit.SECONDS)) { "test gate was never released" }
            failure?.let { throw it }
            val index = pushCount.getAndIncrement()
            calls.add("push pts=$ptsUs finish=$finish")
            val payload =
                if (queuedPayloads.isNotEmpty()) {
                    queuedPayloads.removeAt(0)
                } else {
                    ByteArray(payloadSize)
                }
            // Echo the input timestamp so assertions stay deterministic without sharing
            // mutable state between the loader thread (test) and the worker thread.
            return ByteBuffer.wrap(
                accessUnit(
                    ptsUs = if (hasPts) ptsUs else 1_000L + index,
                    flags = if (index == 0) 1 else 0,
                    width = 1_920,
                    height = 1_080,
                    payload = payload,
                ),
            )
        }

        override fun releaseOutput(output: ByteBuffer) {
            releasedOutputs.incrementAndGet()
        }

        override fun reset() {
            calls.add("reset")
        }
    }

    @Test
    fun deliversSamplesInOrderWithFormat() {
        val trackOutput = RecordingTrackOutput()
        val transcoder = FakeTranscoder()
        // Exceed the fake's single-read chunk so the test requires production to loop
        // until each full sample is stored, mirroring SampleDataQueue.
        transcoder.payloadSize = 3_000
        val reader = TranscodingH262Reader(DeinterlaceMetadataQueue(), transcoderFactory = { transcoder })
        reader.createTracks(FakeExtractorOutput(trackOutput), TsPayloadReader.TrackIdGenerator(0, 1))

        reader.packetStarted(100_000, 0)
        reader.consumeBytes(byteArrayOf(1, 2, 3))
        reader.packetStarted(200_000, 0)
        reader.consumeBytes(byteArrayOf(4, 5))

        await("two samples") { trackOutput.samples.size == 2 }
        assertEquals(
            listOf(Sample(100_000L, C.BUFFER_FLAG_KEY_FRAME, 3_000), Sample(200_000L, 0, 3_000)),
            trackOutput.samples.toList(),
        )
        await("both native outputs released") { transcoder.releasedOutputs.get() == 2 }
        assertEquals(0, trackOutput.storedButUncommittedBytes())
        assertEquals(1, trackOutput.formats.size)
        assertEquals(MimeTypes.VIDEO_H264, trackOutput.formats.single().sampleMimeType)
        assertEquals(1_920, trackOutput.formats.single().width)
        assertEquals(1_080, trackOutput.formats.single().height)
    }

    @Test
    fun setsCsdFromInBandParameterSets() {
        val trackOutput = RecordingTrackOutput()
        val transcoder = FakeTranscoder()
        transcoder.queuedPayloads.add(spsPpsIdrPayload())
        val reader = TranscodingH262Reader(DeinterlaceMetadataQueue(), transcoderFactory = { transcoder })
        reader.createTracks(FakeExtractorOutput(trackOutput), TsPayloadReader.TrackIdGenerator(0, 1))

        reader.packetStarted(100_000, 0)
        reader.consumeBytes(byteArrayOf(1))

        await("one sample") { trackOutput.samples.size == 1 }
        await("format with csd") {
            trackOutput.formats.size == 1 &&
                trackOutput.formats
                    .single()
                    .initializationData
                    .isNotEmpty()
        }
        val format = trackOutput.formats.single()
        assertEquals(MimeTypes.VIDEO_H264, format.sampleMimeType)
        assertEquals(2, format.initializationData.size)
        assertTrue(
            format.initializationData[0].contentEquals(
                byteArrayOf(0, 0, 1, 0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0xD9.toByte(), 0x40),
            ),
        )
        assertTrue(
            format.initializationData[1].contentEquals(
                byteArrayOf(0, 0, 1, 0x68, 0xE9.toByte(), 0x7B, 0xCB.toByte()),
            ),
        )
        assertEquals("avc1.640033", format.codecs)
    }

    @Test
    fun reemitsFormatWhenParameterSetsArriveAfterFirstSample() {
        val trackOutput = RecordingTrackOutput()
        val transcoder = FakeTranscoder()
        // The transcoder emits SPS/PPS once at the stream head; a sample without them must not
        // block later samples, and the delayed pair must update the already-emitted Format.
        transcoder.queuedPayloads.add(ByteArray(5))
        transcoder.queuedPayloads.add(spsPpsIdrPayload())
        val reader = TranscodingH262Reader(DeinterlaceMetadataQueue(), transcoderFactory = { transcoder })
        reader.createTracks(FakeExtractorOutput(trackOutput), TsPayloadReader.TrackIdGenerator(0, 1))

        reader.packetStarted(100_000, 0)
        reader.consumeBytes(byteArrayOf(1))
        reader.packetStarted(200_000, 0)
        reader.consumeBytes(byteArrayOf(2))

        await("two samples") { trackOutput.samples.size == 2 }
        await("re-emitted format") { trackOutput.formats.size == 2 }
        assertTrue(trackOutput.formats[0].initializationData.isEmpty())
        assertEquals(2, trackOutput.formats[1].initializationData.size)
        assertEquals("avc1.640033", trackOutput.formats[1].codecs)
    }

    @Test
    fun extractParameterSetsHandlesStartCodeVariantsAndEmulationPrevention() {
        val payload =
            byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x33) +
                byteArrayOf(0, 0, 1, 0x06, 0x05, 0x00, 0x00, 0x03, 0x01, 0x02) +
                byteArrayOf(0, 0, 0, 1, 0x68, 0xE9.toByte()) +
                byteArrayOf(0, 0, 1, 0x65, 0x11)
        val (sps, pps) =
            TranscodingH262Reader.extractParameterSets(ByteBuffer.wrap(payload), 0, payload.size)
        assertTrue(sps!!.contentEquals(byteArrayOf(0, 0, 1, 0x67, 0x64, 0x00, 0x33)))
        assertTrue(pps!!.contentEquals(byteArrayOf(0, 0, 1, 0x68, 0xE9.toByte())))
        assertNull(TranscodingH262Reader.avcCodecString(byteArrayOf(0, 0, 1, 0x67, 0x64)))
        assertEquals(
            "avc1.640033",
            TranscodingH262Reader.avcCodecString(byteArrayOf(0, 0, 1, 0x67, 0x64, 0x00, 0x33)),
        )
    }

    @Test
    fun consumeDoesNotBlockWhileTranscoderIsBusy() {
        val gate = CountDownLatch(1)
        val trackOutput = RecordingTrackOutput()
        val transcoder = FakeTranscoder(gate = gate)
        val reader = TranscodingH262Reader(DeinterlaceMetadataQueue(), transcoderFactory = { transcoder })
        reader.createTracks(FakeExtractorOutput(trackOutput), TsPayloadReader.TrackIdGenerator(0, 1))

        reader.packetStarted(100_000, 0)
        val startedMs = System.currentTimeMillis()
        reader.consumeBytes(byteArrayOf(1, 2, 3))
        val blockedMs = System.currentTimeMillis() - startedMs
        assertTrue("consume blocked $blockedMs ms on a busy transcoder", blockedMs < 2_000)
        await("transcoder entered push") { transcoder.pushAttempts.get() > 0 }

        gate.countDown()
        await("one sample") { trackOutput.samples.size == 1 }
    }

    @Test
    fun seekResetsNativeStateBetweenGenerations() {
        val trackOutput = RecordingTrackOutput()
        val transcoder = FakeTranscoder()
        val metadata = DeinterlaceMetadataQueue()
        val reader = TranscodingH262Reader(metadata, transcoderFactory = { transcoder })
        reader.createTracks(FakeExtractorOutput(trackOutput), TsPayloadReader.TrackIdGenerator(0, 1))

        reader.packetStarted(100_000, 0)
        reader.consumeBytes(byteArrayOf(1))
        reader.seek()
        reader.packetStarted(200_000, 0)
        reader.consumeBytes(byteArrayOf(2))

        await("two samples") { trackOutput.samples.size == 2 }
        await("push after reset") { transcoder.calls.count { it.startsWith("push") } == 2 }
        assertEquals(
            listOf("push pts=100000 finish=false", "reset", "push pts=200000 finish=false"),
            transcoder.calls.toList(),
        )
        assertNull(metadata.take(100_000))
        assertNotNull(metadata.take(200_000))
    }

    @Test
    fun workerFailureIsRethrownOnLoaderThread() {
        val trackOutput = RecordingTrackOutput()
        val transcoder = FakeTranscoder(failure = IllegalStateException("boom"))
        val reader = TranscodingH262Reader(DeinterlaceMetadataQueue(), transcoderFactory = { transcoder })
        reader.createTracks(FakeExtractorOutput(trackOutput), TsPayloadReader.TrackIdGenerator(0, 1))

        reader.packetStarted(100_000, 0)
        reader.consumeBytes(byteArrayOf(1))
        await("transcoder attempted push") { transcoder.pushAttempts.get() > 0 }

        try {
            var attempts = 0
            while (true) {
                try {
                    reader.packetStarted(200_000, 0)
                    reader.consumeBytes(byteArrayOf(2))
                } catch (expected: ParserException) {
                    assertTrue(expected.message.orEmpty().contains("boom"))
                    return
                }
                if (++attempts > 500) fail("worker error was never delivered to the loader thread")
                Thread.sleep(10)
            }
        } catch (error: AssertionError) {
            throw error
        }
    }

    private fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadlineMs = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadlineMs) fail("timed out waiting for $what")
            Thread.sleep(10)
        }
    }
}

private fun spsPpsIdrPayload(): ByteArray =
    byteArrayOf(0, 0, 0, 1, 0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0xD9.toByte(), 0x40) +
        byteArrayOf(0, 0, 1, 0x68, 0xE9.toByte(), 0x7B, 0xCB.toByte()) +
        byteArrayOf(0, 0, 0, 1, 0x65, 0x11, 0x22)

private fun accessUnit(
    ptsUs: Long,
    flags: Int,
    width: Int,
    height: Int,
    payload: ByteArray = byteArrayOf(0, 0, 0, 1, 9),
): ByteArray {
    val buffer =
        ByteBuffer
            .allocate(4 + 4 + 4 + 8 + 8 + 4 + 4 * 4 + 4 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(2)
    buffer.putInt(0)
    buffer.putInt(1)
    buffer.putLong(ptsUs)
    buffer.putLong(33_366)
    buffer.putInt(flags)
    buffer.putInt(width)
    buffer.putInt(height)
    buffer.putInt(1)
    buffer.putInt(1)
    buffer.putInt(payload.size)
    buffer.put(payload)
    return buffer.array()
}
