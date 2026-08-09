package com.example.application

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class AudioPlayer {

    private var audioTrack: AudioTrack? = null
    private val pcmQueue = LinkedBlockingQueue<ByteArray>(1000) // Max 1000 chunks (~5s buffer)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var playbackJob: Job? = null

    val playbackUnderruns = AtomicLong(0)

    @Volatile
    private var isPlaying = false

    private val PREBUFFER_CHUNKS = 10 // ~100ms of audio prebuffer

    fun start() {
        stop()

        playbackUnderruns.set(0)
        pcmQueue.clear()

        val sampleRate = 48000
        val channelConfig = AudioFormat.CHANNEL_OUT_STEREO
        val audioEncoding = AudioFormat.ENCODING_PCM_FLOAT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        // Set AudioTrack buffer size to at least 250ms of 48kHz stereo float (96,000 bytes)
        val bufferSize = Math.max(minBufferSize, 48000 * 8 * 25 / 100)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .setEncoding(audioEncoding)
            .build()

        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        audioTrack = track
        isPlaying = true

        playbackJob = scope.launch {
            var floatBuffer = FloatArray(1024)
            var isPrebuffering = true

            while (isActive && isPlaying) {
                if (isPrebuffering) {
                    if (pcmQueue.size < PREBUFFER_CHUNKS) {
                        try {
                            Thread.sleep(5)
                        } catch (e: InterruptedException) {
                            break
                        }
                        continue
                    } else {
                        isPrebuffering = false
                    }
                }

                val chunk = try {
                    pcmQueue.poll(50, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                }

                if (chunk != null && chunk.isNotEmpty()) {
                    val floatCount = chunk.size / 4
                    if (floatCount > 0) {
                        if (floatBuffer.size < floatCount) {
                            floatBuffer = FloatArray(floatCount)
                        }

                        val byteBuf = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN)
                        var idx = 0
                        while (idx < floatCount && byteBuf.hasRemaining()) {
                            floatBuffer[idx] = byteBuf.float
                            idx++
                        }

                        var offset = 0
                        var remaining = floatCount
                        while (remaining > 0 && isPlaying && isActive) {
                            val written = track.write(floatBuffer, offset, remaining, AudioTrack.WRITE_BLOCKING)
                            if (written < 0) {
                                playbackUnderruns.incrementAndGet()
                                break
                            } else if (written == 0) {
                                try {
                                    Thread.sleep(1)
                                } catch (e: InterruptedException) {
                                    break
                                }
                            } else {
                                offset += written
                                remaining -= written
                            }
                        }
                    }
                } else {
                    // Timeout occurred: queue ran dry, re-enable prebuffering
                    isPrebuffering = true
                }
            }
        }
    }

    fun submitPcm(pcmData: ByteArray) {
        if (!isPlaying || pcmData.isEmpty()) return
        if (!pcmQueue.offer(pcmData)) {
            // Queue overflow / overrun: drop oldest chunk to prevent unbounded growth
            pcmQueue.poll()
            pcmQueue.offer(pcmData)
            playbackUnderruns.incrementAndGet()
        }
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null

        pcmQueue.clear()

        val track = audioTrack
        audioTrack = null
        if (track != null) {
            try {
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                }
                track.release()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
