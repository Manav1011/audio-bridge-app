package com.example.application

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

enum class AudioSourceMode {
    UNPROCESSED,
    MIC,
    AUTO,
    TEST_TONE,
    UNPROCESSED_TRANSPORT_DIAG
}

class AudioRecorder {

    private var audioRecord: AudioRecord? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var recordJob: Job? = null

    val recorderOverruns = AtomicLong(0)

    var onPcmCaptured: ((ByteArray) -> Unit)? = null
    var onDiagnosticLog: ((String) -> Unit)? = null

    var diagnosticOutputDir: File? = null

    @Volatile
    private var isRecording = false
    private var activeWavWriter: DiagnosticWavWriter? = null
    private var activeHashWriter: DiagnosticHashWriter? = null
    private val currentChunkIndex = AtomicLong(0)

    var actualCaptureFormatDescription: String = "Not initialized"
        private set

    private fun safeLog(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (e: Throwable) {
            // Ignored in plain JVM unit tests
        }
    }

    private fun logDiag(msg: String) {
        safeLog("AudioRecorderDiag", msg)
        onDiagnosticLog?.invoke(msg)
    }

    @SuppressLint("MissingPermission")
    fun start(sourceMode: AudioSourceMode, outputDir: File? = null) {
        stop()

        if (outputDir != null) {
            diagnosticOutputDir = outputDir
        }

        recorderOverruns.set(0)
        currentChunkIndex.set(0)
        isRecording = true

        val dir = diagnosticOutputDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val fileName = when (sourceMode) {
            AudioSourceMode.UNPROCESSED -> "unprocessed_capture.wav"
            AudioSourceMode.MIC -> "mic_capture.wav"
            AudioSourceMode.TEST_TONE -> "test_tone_capture.wav"
            AudioSourceMode.AUTO -> "microphone_capture.wav"
            AudioSourceMode.UNPROCESSED_TRANSPORT_DIAG -> "unprocessed_transport_source.wav"
        }
        val targetFile = File(dir, fileName)

        val sourceTagStr = when (sourceMode) {
            AudioSourceMode.UNPROCESSED, AudioSourceMode.UNPROCESSED_TRANSPORT_DIAG -> "UNPROCESSED"
            AudioSourceMode.MIC -> "MIC"
            AudioSourceMode.TEST_TONE -> "Test Tone"
            AudioSourceMode.AUTO -> "AUTO"
        }

        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            activeWavWriter = DiagnosticWavWriter(targetFile, sourceTagStr)
            if (sourceMode == AudioSourceMode.UNPROCESSED_TRANSPORT_DIAG) {
                val hashFile = File(dir, "unprocessed_transport_hashes.txt")
                activeHashWriter = DiagnosticHashWriter(hashFile)
                logDiag("TRANSPORT DIAG SOURCE: UNPROCESSED")
                logDiag("TRANSPORT DIAG FORMAT: 48000 Hz / stereo / Float32")
            } else {
                logDiag("DIAG WAV: Initialized WAV capture writer for $sourceTagStr at ${targetFile.absolutePath}")
            }
        } catch (e: Exception) {
            logDiag("DIAG WAV: Failed to initialize writers: ${e.message}")
            activeWavWriter = null
            activeHashWriter = null
        }

        if (sourceMode == AudioSourceMode.TEST_TONE) {
            actualCaptureFormatDescription = "Deterministic Test Tone Generator (440 Hz Sine Wave, 48kHz Stereo Float32 LE)"
            startTestToneGenerator()
        } else {
            startHardwareCapture(sourceMode)
        }
    }

    @SuppressLint("MissingPermission")
    fun startUnprocessedTransportDiagnostic(outputDir: File? = null, useTestTone: Boolean = false) {
        stop()

        if (outputDir != null) {
            diagnosticOutputDir = outputDir
        }

        recorderOverruns.set(0)
        currentChunkIndex.set(0)
        isRecording = true

        val dir = diagnosticOutputDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val targetFile = File(dir, "unprocessed_transport_source.wav")
        val hashFile = File(dir, "unprocessed_transport_hashes.txt")

        try {
            if (!dir.exists()) {
                dir.mkdirs()
            }
            activeWavWriter = DiagnosticWavWriter(targetFile, "UNPROCESSED")
            activeHashWriter = DiagnosticHashWriter(hashFile)
            logDiag("TRANSPORT DIAG SOURCE: UNPROCESSED")
            logDiag("TRANSPORT DIAG FORMAT: 48000 Hz / stereo / Float32")
        } catch (e: Exception) {
            logDiag("DIAG WAV: Failed to initialize writers: ${e.message}")
            activeWavWriter = null
            activeHashWriter = null
        }

        if (useTestTone) {
            actualCaptureFormatDescription = "UNPROCESSED Diagnostic Test Tone Generator (48kHz Stereo Float32 LE)"
            startTestToneGenerator()
        } else {
            startHardwareCapture(AudioSourceMode.UNPROCESSED_TRANSPORT_DIAG)
        }
    }

    @SuppressLint("MissingPermission")
    fun start(useTestTone: Boolean, outputDir: File? = null) {
        val mode = if (useTestTone) AudioSourceMode.TEST_TONE else AudioSourceMode.AUTO
        start(mode, outputDir)
    }

    private fun dispatchPcmCaptured(outBytes: ByteArray) {
        require(outBytes.size % 8 == 0) {
            "Captured PCM chunk size (${outBytes.size}) must be a multiple of 8 bytes (canonical stereo Float32 LE frames)"
        }
        activeWavWriter?.writeChunk(outBytes)
        activeHashWriter?.let { hashWriter ->
            val hash = sha256Hex(outBytes)
            val idx = currentChunkIndex.getAndIncrement()
            hashWriter.writeHash(idx, outBytes.size, hash)
        }
        onPcmCaptured?.invoke(outBytes)
    }

    private fun sha256Hex(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    @SuppressLint("MissingPermission")
    private fun startHardwareCapture(sourceMode: AudioSourceMode = AudioSourceMode.AUTO) {
        var record: AudioRecord? = null
        var actualNativeRate = 48000

        val audioSourceToUse = if (sourceMode == AudioSourceMode.UNPROCESSED_TRANSPORT_DIAG || sourceMode == AudioSourceMode.UNPROCESSED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                MediaRecorder.AudioSource.UNPROCESSED
            } else {
                MediaRecorder.AudioSource.MIC
            }
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val candidateRates = intArrayOf(48000, 44100, 32000, 24000, 22050, 16000, 11025, 8000)

        for (rate in candidateRates) {
            val minBufSize = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufSize > 0) {
                try {
                    val bufSize = maxOf(minBufSize * 4, 4096)
                    val rec = AudioRecord(
                        audioSourceToUse,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufSize
                    )
                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                        record = rec
                        actualNativeRate = rec.sampleRate
                        break
                    } else {
                        rec.release()
                    }
                } catch (e: Exception) {
                    // Try next candidate rate
                }
            }
        }

        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            actualCaptureFormatDescription = "FAILED: MIC AudioRecord initialization failed"
            logDiag("MIC CAPTURE ERROR: MIC AudioRecord initialization failed on all sample rates!")
            isRecording = false
            return
        }

        val sourceTag = if (audioSourceToUse == MediaRecorder.AudioSource.UNPROCESSED) "UNPROCESSED" else "MIC"
        logDiag("MIC CAPTURE SOURCE: $sourceTag")
        logDiag("MIC CAPTURE NATIVE FORMAT: $actualNativeRate Hz / mono / PCM16")
        logDiag("TRANSPORT FORMAT: 48000 Hz / stereo / Float32 LE")

        actualCaptureFormatDescription = "Hardware $sourceTag ($actualNativeRate Hz, Mono PCM16) -> Converted to 48000 Hz Stereo Float32 LE"

        audioRecord = record
        record.startRecording()

        recordJob = scope.launch {
            val converter = Pcm16MonoToFloat32StereoConverter(actualNativeRate, 48000)
            val safeChunkSamples = (actualNativeRate / 100) // ~10ms chunk at native rate
            val shortBuffer = ShortArray(maxOf(safeChunkSamples, 320))
            var readIndex = 0

            while (isActive && isRecording) {
                val readShorts = record.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)

                if (readIndex < 50) {
                    readIndex++
                    logDiag("MIC READ #$readIndex: returned $readShorts shorts at $actualNativeRate Hz")
                }

                if (readShorts > 0) {
                    val pcmOut = converter.convertAndResample(shortBuffer, readShorts)
                    if (pcmOut.isNotEmpty()) {
                        dispatchPcmCaptured(pcmOut)
                    }
                } else if (readShorts < 0) {
                    recorderOverruns.incrementAndGet()
                }
            }
        }
    }

    private fun startTestToneGenerator() {
        recordJob = scope.launch {
            var frameIndex = 0L
            val sampleRate = 48000.0
            val frequency = 440.0
            val angularFrequency = 2.0 * Math.PI * frequency / sampleRate

            // Every 10 ms chunk = 480 stereo frames = 960 floats = 3840 bytes
            val chunkFrames = 480
            val byteBuffer = ByteBuffer.allocate(chunkFrames * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)

            while (isActive && isRecording) {
                byteBuffer.clear()
                for (i in 0 until chunkFrames) {
                    val floatVal = (Math.sin(frameIndex * angularFrequency) * 0.25).toFloat()
                    byteBuffer.putFloat(floatVal) // Left channel
                    byteBuffer.putFloat(floatVal) // Right channel
                    frameIndex++
                }

                val outBytes = ByteArray(byteBuffer.capacity())
                System.arraycopy(byteBuffer.array(), 0, outBytes, 0, outBytes.size)
                dispatchPcmCaptured(outBytes)

                try {
                    Thread.sleep(10) // 10ms pacing
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
    }

    fun stop() {
        isRecording = false
        recordJob?.cancel()
        recordJob = null

        val writer = activeWavWriter
        activeWavWriter = null

        val hashWriter = activeHashWriter
        activeHashWriter = null

        if (writer != null) {
            val stats = writer.closeAndGetStats()
            if (hashWriter != null) {
                val (hashPath, chunks) = hashWriter.closeAndGetStats()
                logDiag("TRANSPORT DIAG COMPLETE")
                logDiag("SOURCE: UNPROCESSED")
                logDiag("FRAMES: ${stats.frames}")
                logDiag("BYTES: ${stats.totalBytes}")
                logDiag("CHUNKS: $chunks")
                logDiag("WAV: ${stats.filePath}")
                logDiag("HASHES: $hashPath")
            } else {
                logDiag("DIAG SOURCE: ${stats.sourceTag}")
                logDiag("DIAG FORMAT: 48000 Hz / stereo / Float32")
                logDiag("DIAG FRAMES: ${stats.frames}")
                logDiag("DIAG BYTES: ${stats.totalBytes}")
                logDiag("DIAG PATH: ${stats.filePath}")
            }
        } else if (hashWriter != null) {
            hashWriter.closeAndGetStats()
        }

        val rec = audioRecord
        audioRecord = null
        if (rec != null) {
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
                rec.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}

private class DiagnosticHashWriter(private val file: File) {
    private var writer: java.io.FileWriter? = null
    private var chunkCount: Long = 0

    init {
        try {
            if (file.exists()) {
                file.delete()
            }
            writer = java.io.FileWriter(file, true)
        } catch (e: Exception) {
            // Ignored or logged safely
        }
    }

    @Synchronized
    fun writeHash(chunkIndex: Long, bytesSize: Int, sha256Hex: String) {
        val w = writer ?: return
        try {
            w.write("chunk=$chunkIndex bytes=$bytesSize sha256=$sha256Hex\n")
            w.flush()
            chunkCount++
        } catch (e: Exception) {
            // Ignored or logged safely
        }
    }

    @Synchronized
    fun closeAndGetStats(): Pair<String, Long> {
        val path = file.absolutePath
        val w = writer
        if (w != null) {
            try {
                w.close()
            } catch (e: Exception) {
                // Ignored
            } finally {
                writer = null
            }
        }
        return Pair(path, chunkCount)
    }
}

data class WavStats(
    val sourceTag: String,
    val totalBytes: Long,
    val frames: Long,
    val filePath: String
)

private class DiagnosticWavWriter(private val file: File, private val sourceTag: String) {
    private var randomAccessFile: RandomAccessFile? = null
    private var totalPcmBytes: Long = 0

    init {
        try {
            if (file.exists()) {
                file.delete()
            }
            val raf = RandomAccessFile(file, "rw")
            // Write 44-byte placeholder WAV header
            val header = createWavHeader(0)
            raf.write(header)
            randomAccessFile = raf
        } catch (e: Exception) {
            // Ignored or logged safely
        }
    }

    @Synchronized
    fun writeChunk(pcm: ByteArray) {
        val raf = randomAccessFile ?: return
        try {
            raf.write(pcm)
            totalPcmBytes += pcm.size
        } catch (e: Exception) {
            // Ignored or logged safely
        }
    }

    @Synchronized
    fun closeAndGetStats(): WavStats {
        val path = file.absolutePath
        val raf = randomAccessFile
        if (raf != null) {
            try {
                val totalFileSize = totalPcmBytes + 36
                raf.seek(4)
                raf.write(intToByteArrayLE(totalFileSize.toInt()))
                raf.seek(40)
                raf.write(intToByteArrayLE(totalPcmBytes.toInt()))
                raf.close()
            } catch (e: Exception) {
                // Ignored or logged safely
            } finally {
                randomAccessFile = null
            }
        }
        val frames = totalPcmBytes / 8 // 2 channels * 4 bytes/float = 8 bytes/frame
        return WavStats(sourceTag, totalPcmBytes, frames, path)
    }

    private fun createWavHeader(pcmDataSize: Int): ByteArray {
        val totalFileSize = pcmDataSize + 36
        val sampleRate = 48000
        val channels = 2
        val bitsPerSample = 32
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)

        val header = ByteArray(44)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(totalFileSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt chunk
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16) // Subchunk1Size
        buf.putShort(3.toShort()) // AudioFormat = 3 (WAVE_FORMAT_IEEE_FLOAT)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign.toShort())
        buf.putShort(bitsPerSample.toShort())

        // data chunk
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(pcmDataSize)

        return header
    }

    private fun intToByteArrayLE(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }
}

class Pcm16MonoToFloat32StereoConverter(
    val nativeSampleRate: Int,
    val targetSampleRate: Int = 48000
) {
    private val step: Double = nativeSampleRate.toDouble() / targetSampleRate.toDouble()
    private var lastSample: Float = 0.0f
    private var phase: Double = 0.0

    /**
     * Converts native PCM16 mono shorts to Float32 LE stereo interleaved bytes (48kHz).
     * Handles partial reads (readCount <= shortBuffer.size).
     */
    fun convertAndResample(shortBuffer: ShortArray, readCount: Int): ByteArray {
        if (readCount <= 0) return ByteArray(0)

        // 1. Convert PCM16 mono shorts to Float32 [-1.0f, +0.999969f]
        val inputFloats = FloatArray(readCount)
        for (i in 0 until readCount) {
            inputFloats[i] = shortBuffer[i] / 32768.0f
        }

        if (nativeSampleRate == targetSampleRate) {
            // Direct 1:1 mono -> stereo float32 conversion
            val byteBuffer = ByteBuffer.allocate(readCount * 2 * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until readCount) {
                val f = inputFloats[i]
                byteBuffer.putFloat(f) // Left
                byteBuffer.putFloat(f) // Right
            }
            return byteBuffer.array()
        }

        // Resampling with linear interpolation from nativeSampleRate to targetSampleRate
        val maxFrames = (readCount / step).toInt() + 10
        val byteBuffer = ByteBuffer.allocate(maxFrames * 8).order(ByteOrder.LITTLE_ENDIAN)

        var p = phase - 1.0
        val limit = readCount - 1
        while (p < limit) {
            val idxA = Math.floor(p).toInt()
            val sA = if (idxA < 0) lastSample else inputFloats[idxA]
            val sB = inputFloats[idxA + 1]
            val alpha = (p - idxA).toFloat()
            val y = sA + alpha * (sB - sA)

            byteBuffer.putFloat(y) // Left
            byteBuffer.putFloat(y) // Right

            p += step
        }

        phase = p - limit
        lastSample = inputFloats[readCount - 1]

        val outBytes = ByteArray(byteBuffer.position())
        System.arraycopy(byteBuffer.array(), 0, outBytes, 0, outBytes.size)
        return outBytes
    }
}


