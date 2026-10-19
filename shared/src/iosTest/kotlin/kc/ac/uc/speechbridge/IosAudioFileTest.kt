@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
package kc.ac.uc.speechbridge

import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.*
import kotlin.test.*

class IosAudioFileTest {
    @Test fun streamingHardwareBuffersConvertToMono16kWithoutMicrophone() {
        for(rate in listOf(16000.0, 44100.0, 48000.0)) {
            val format = AVAudioFormat(AVAudioPCMFormatFloat32, rate, 2u, false)
            val converter = IosPcmConverter(format)
            val buffer = AVAudioPCMBuffer(format, (rate / 10).toUInt())
            buffer.frameLength = (rate / 10).toUInt()
            repeat(2) { channel -> repeat(buffer.frameLength.toInt()) { index ->
                buffer.floatChannelData!![channel]!![index] = (kotlin.math.sin(index * 2 * kotlin.math.PI * 440 / rate) * .25).toFloat()
            } }
            val frames = (0 until 10).flatMap { converter.convert(buffer).toList() }
            assertTrue(frames.size in 15000..16100, "rate=$rate frames=${frames.size}")
            assertTrue(frames.any { it > 1000 })
            PcmWave.decode(PcmWave.encode(frames.toShortArray(),16000),16000)
        }
    }

    private fun fixture(block: (NSURL) -> Unit) {
        val file = NSURL.fileURLWithPath(NSTemporaryDirectory() + "speechbridge-audio-test-${newId()}.wav")
        try { block(file) } finally { NSFileManager.defaultManager.removeItemAtURL(file, null) }
    }
    private fun write(file: NSURL, frames: Int, rate: Double = 16000.0) {
        val format = AVAudioFormat(AVAudioPCMFormatInt16, rate, 1u, true)
        val buffer = AVAudioPCMBuffer(format, frames.toUInt())
        buffer.frameLength = frames.toUInt()
        repeat(frames) { buffer.int16ChannelData!![0]!![it] = (it % 2000 - 1000).toShort() }
        val output = AVAudioFile(forWriting = file, settings = format.settings,
            commonFormat = AVAudioPCMFormatInt16, interleaved = true, error = null)
        assertTrue(output.writeFromBuffer(buffer, null))
        output.close()
    }
    @Test fun nativeWrittenFileNormalizesToCanonicalUploadWithoutMicrophone() = fixture { file ->
        write(file, 16000)
        val wav = readIosRecording(file)
        assertEquals(32044, wav.size)
        assertContentEquals(ShortArray(16000) { (it % 2000 - 1000).toShort() }, PcmWave.decode(wav, 16000).samples)
    }
    @Test fun shortRecordingHasActionableError() = fixture { file ->
        write(file, 800)
        assertEquals("no_speech", assertFailsWith<BridgeRequestException> { readIosRecording(file) }.code)
    }
    @Test fun corruptFileHasReadErrorInsteadOfGenericAudioFailure() = fixture { file ->
        val bytes = byteArrayOf(1,2,3,4)
        bytes.usePinned { assertTrue(NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()).writeToURL(file, true)) }
        assertEquals("audio_read_failed", assertFailsWith<BridgeRequestException> { readIosRecording(file) }.code)
    }
    @Test fun wrongSampleRateCannotBeUploadedAs16kHz() = fixture { file ->
        write(file, 2400, 24000.0)
        assertEquals("audio_format_invalid", assertFailsWith<BridgeRequestException> { readIosRecording(file) }.code)
    }
    @Test fun emptyAndOverlongRecordingsAreRejectedBeforeUpload() = fixture { file ->
        write(file, 0)
        assertEquals("no_speech", assertFailsWith<BridgeRequestException> { readIosRecording(file) }.code)
        write(file, 480001)
        assertEquals("audio_too_long", assertFailsWith<BridgeRequestException> { readIosRecording(file) }.code)
    }

}
