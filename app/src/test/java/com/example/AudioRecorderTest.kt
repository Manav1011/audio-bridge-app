package com.example

import com.example.application.AudioRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AudioRecorderTest {

    @Test
    fun `test 440 Hz test tone generation produces 48kHz stereo float32 LE PCM with amplitude 0,25`() {
        val recorder = AudioRecorder()
        val latch = CountDownLatch(1)
        var capturedPcm: ByteArray? = null

        recorder.onPcmCaptured = { pcm ->
            if (capturedPcm == null) {
                capturedPcm = pcm
                latch.countDown()
            }
        }

        recorder.start(useTestTone = true)
        val received = latch.await(2, TimeUnit.SECONDS)
        recorder.stop()

        assertTrue("Test tone should emit PCM captured callback", received)
        assertNotNull(capturedPcm)

        val pcm = capturedPcm!!
        // 480 frames * 2 channels * 4 bytes/float = 3840 bytes
        assertEquals(3840, pcm.size)

        // Verify float32 little-endian values
        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var maxAmplitude = 0.0f
        while (buf.hasRemaining()) {
            val leftSample = buf.float
            val rightSample = buf.float
            // Left and right channels should be equal in sine tone
            assertEquals(leftSample, rightSample, 0.0001f)
            if (Math.abs(leftSample) > maxAmplitude) {
                maxAmplitude = Math.abs(leftSample)
            }
        }

        // Peak amplitude should approach 0.25f
        assertTrue("Max amplitude should be around 0.25, actual: $maxAmplitude", maxAmplitude <= 0.251f && maxAmplitude > 0.01f)
    }

    @Test
    fun `test unprocessed transport diagnostic writes identical PCM bytes to WAV and hash log as passed to onPcmCaptured`() {
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "diag_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        val recorder = AudioRecorder()
        val latch = CountDownLatch(3)
        val capturedChunks = mutableListOf<ByteArray>()

        recorder.onPcmCaptured = { pcm ->
            synchronized(capturedChunks) {
                if (capturedChunks.size < 3) {
                    capturedChunks.add(pcm.clone())
                    latch.countDown()
                }
            }
        }

        recorder.startUnprocessedTransportDiagnostic(outputDir = tempDir, useTestTone = true)
        val received = latch.await(2, TimeUnit.SECONDS)
        recorder.stop()

        assertTrue("Recorder should emit PCM chunks in transport diagnostic mode", received)
        assertEquals(3, capturedChunks.size)

        val wavFile = java.io.File(tempDir, "unprocessed_transport_source.wav")
        val hashFile = java.io.File(tempDir, "unprocessed_transport_hashes.txt")

        assertTrue("unprocessed_transport_source.wav should exist", wavFile.exists())
        assertTrue("unprocessed_transport_hashes.txt should exist", hashFile.exists())

        // Verify WAV payload bytes match captured PCM bytes
        val wavBytes = wavFile.readBytes()
        assertTrue("WAV size must be greater than header", wavBytes.size > 44)
        val wavPcm = wavBytes.copyOfRange(44, wavBytes.size)

        var totalCapturedBytes = 0
        capturedChunks.forEach { totalCapturedBytes += it.size }

        assertTrue("WAV payload size must be >= captured bytes", wavPcm.size >= totalCapturedBytes)

        // Verify first chunk in WAV matches first captured chunk exactly
        val firstChunkSize = capturedChunks[0].size
        val wavFirstChunk = wavPcm.copyOfRange(0, firstChunkSize)
        org.junit.Assert.assertArrayEquals("WAV PCM bytes must match captured PCM bytes exactly", capturedChunks[0], wavFirstChunk)

        // Verify SHA-256 hash log
        val hashLines = hashFile.readLines().filter { it.isNotBlank() }
        assertTrue("Hash log should contain entries", hashLines.isNotEmpty())

        val md = java.security.MessageDigest.getInstance("SHA-256")
        val expectedHash0Bytes = md.digest(capturedChunks[0])
        val expectedHash0Hex = expectedHash0Bytes.joinToString("") { String.format("%02x", it.toInt() and 0xFF) }

        assertTrue("Hash log chunk=0 should match computed SHA-256 ($expectedHash0Hex vs ${hashLines[0]})", hashLines[0].contains("sha256=$expectedHash0Hex"))

        // Cleanup
        tempDir.deleteRecursively()
    }

    @Test
    fun `test PCM16 mono to Float32 conversion and normalization for full scale negative, zero, and positive values`() {
        val converter = com.example.application.Pcm16MonoToFloat32StereoConverter(nativeSampleRate = 48000, targetSampleRate = 48000)
        val inputShorts = shortArrayOf(-32768, 0, 32767)

        val outBytes = converter.convertAndResample(inputShorts, readCount = 3)

        // 3 mono samples -> 3 stereo frames * 2 channels * 4 bytes = 24 bytes
        assertEquals(24, outBytes.size)
        assertEquals(0, outBytes.size % 8)

        val buf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)

        // Frame 0: -32768 -> -1.0f
        val left0 = buf.float
        val right0 = buf.float
        assertEquals(-1.0f, left0, 0.00001f)
        assertEquals(-1.0f, right0, 0.00001f)

        // Frame 1: 0 -> 0.0f
        val left1 = buf.float
        val right1 = buf.float
        assertEquals(0.0f, left1, 0.00001f)
        assertEquals(0.0f, right1, 0.00001f)

        // Frame 2: 32767 -> +0.9999695f
        val left2 = buf.float
        val right2 = buf.float
        assertEquals(32767 / 32768.0f, left2, 0.00001f)
        assertEquals(32767 / 32768.0f, right2, 0.00001f)
    }

    @Test
    fun `test partial PCM16 reads process only readCount samples`() {
        val converter = com.example.application.Pcm16MonoToFloat32StereoConverter(nativeSampleRate = 48000, targetSampleRate = 48000)
        val buffer = ShortArray(100)
        buffer[0] = 16384
        buffer[1] = -16384

        val outBytes = converter.convertAndResample(buffer, readCount = 2)

        // 2 mono samples -> 2 stereo frames = 16 bytes
        assertEquals(16, outBytes.size)
        assertEquals(0, outBytes.size % 8)

        val buf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0.5f, buf.float, 0.00001f) // L0
        assertEquals(0.5f, buf.float, 0.00001f) // R0
        assertEquals(-0.5f, buf.float, 0.00001f) // L1
        assertEquals(-0.5f, buf.float, 0.00001f) // R1
    }

    @Test
    fun `test mono to stereo duplication produces identical left and right channel values`() {
        val converter = com.example.application.Pcm16MonoToFloat32StereoConverter(nativeSampleRate = 48000, targetSampleRate = 48000)
        val inputShorts = shortArrayOf(1234, -5678, 20000, -30000)

        val outBytes = converter.convertAndResample(inputShorts, readCount = 4)
        assertEquals(32, outBytes.size)

        val buf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.hasRemaining()) {
            val left = buf.float
            val right = buf.float
            assertEquals(left, right, 0.00001f)
        }
    }

    @Test
    fun `test resampling when native rate is 16000 Hz converts to 48000 Hz stereo Float32 LE`() {
        val converter = com.example.application.Pcm16MonoToFloat32StereoConverter(nativeSampleRate = 16000, targetSampleRate = 48000)
        val native10msShorts = ShortArray(160) { (it * 100).toShort() }

        val outBytes = converter.convertAndResample(native10msShorts, readCount = 160)

        assertTrue("Output bytes must not be empty", outBytes.isNotEmpty())
        assertEquals("Output bytes size must be multiple of 8 (stereo Float32 frames)", 0, outBytes.size % 8)

        val buf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        var frameCount = 0
        while (buf.hasRemaining()) {
            val left = buf.float
            val right = buf.float
            assertEquals("Stereo channels must be identical", left, right, 0.00001f)
            frameCount++
        }

        // 16kHz -> 48kHz (3x ratio), 160 native samples produces ~480 frames
        assertTrue("Frame count should be approximately 480, actual: $frameCount", frameCount in 470..490)
    }

    @Test
    fun `test resampling when native rate is 44100 Hz produces 8-byte aligned 48000 Hz stereo Float32 LE`() {
        val converter = com.example.application.Pcm16MonoToFloat32StereoConverter(nativeSampleRate = 44100, targetSampleRate = 48000)
        val native441Shorts = ShortArray(441) { (Math.sin(it * 0.1) * 20000).toInt().toShort() }

        val outBytes = converter.convertAndResample(native441Shorts, readCount = 441)

        assertTrue("Output bytes must not be empty", outBytes.isNotEmpty())
        assertEquals("Output bytes size must be multiple of 8", 0, outBytes.size % 8)

        val buf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        var frameCount = 0
        while (buf.hasRemaining()) {
            val left = buf.float
            val right = buf.float
            assertEquals("Left and Right channels must be equal", left, right, 0.00001f)
            frameCount++
        }

        // 44.1kHz -> 48kHz, 441 samples produces ~480 frames
        assertTrue("Frame count should be approximately 480, actual: $frameCount", frameCount in 470..490)
    }
}

