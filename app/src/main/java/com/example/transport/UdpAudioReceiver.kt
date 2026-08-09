package com.example.transport

import com.example.protocol.AudioPacket
import com.example.protocol.AudioPacketCodec
import com.example.protocol.PacketDecodeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class UdpAudioReceiver {

    private var socket: DatagramSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null

    var onPcmReceived: ((ByteArray) -> Unit)? = null

    val rxPackets = AtomicLong(0)
    val rxBytes = AtomicLong(0)
    val sequenceGaps = AtomicLong(0)
    val duplicates = AtomicLong(0)
    val outOfOrder = AtomicLong(0)
    val malformedPackets = AtomicLong(0)

    private var nextExpectedSeq: Long? = null
    private val reorderBuffer = ConcurrentHashMap<Long, ByteArray>()
    private val maxReorderBufferSize = 64

    // Diagnostic byte-capture variables
    private var captureFile: File? = null
    private var captureQueue: LinkedBlockingQueue<ByteArray>? = null
    private var captureWriterThread: Thread? = null
    @Volatile private var isCapturing = false

    var lastSavedCapturePath: String? = null
        private set
    var lastSavedCaptureSize: Long = 0L
        private set

    @Synchronized
    fun isListening(): Boolean {
        return socket?.let { !it.isClosed && it.isBound } ?: false
    }

    fun start(rxPort: Int, outputDir: File? = null) {
        stop()

        rxPackets.set(0)
        rxBytes.set(0)
        sequenceGaps.set(0)
        duplicates.set(0)
        outOfOrder.set(0)
        malformedPackets.set(0)
        nextExpectedSeq = null
        reorderBuffer.clear()

        // Setup diagnostic PCM capture file for this stream session
        val targetDir = outputDir ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(targetDir, "speaker_udp_capture_$timestamp.pcm")
        captureFile = file
        lastSavedCapturePath = file.absolutePath
        lastSavedCaptureSize = 0L

        val queue = LinkedBlockingQueue<ByteArray>(10000)
        captureQueue = queue
        isCapturing = true

        val writerThread = Thread({
            var fos: FileOutputStream? = null
            var bos: BufferedOutputStream? = null
            try {
                fos = FileOutputStream(file, false)
                bos = BufferedOutputStream(fos, 64 * 1024)
                while (isCapturing || !queue.isEmpty()) {
                    val pcmChunk = queue.poll(50, TimeUnit.MILLISECONDS)
                    if (pcmChunk != null) {
                        bos.write(pcmChunk)
                    }
                }
                bos.flush()
                fos.fd.sync()
            } catch (e: Exception) {
                // Ignore diagnostic write errors
            } finally {
                try { bos?.close() } catch (ignored: Exception) {}
                try { fos?.close() } catch (ignored: Exception) {}
                lastSavedCaptureSize = if (file.exists()) file.length() else 0L
            }
        }, "SpeakerUdpCaptureWriter")
        writerThread.start()
        captureWriterThread = writerThread

        val newSocket = DatagramSocket(rxPort).apply {
            soTimeout = 0
            reuseAddress = true
        }
        socket = newSocket

        receiveJob = scope.launch {
            val buffer = ByteArray(2048)
            while (isActive && !newSocket.isClosed) {
                try {
                    val datagramPacket = DatagramPacket(buffer, buffer.size)
                    newSocket.receive(datagramPacket)

                    val totalBytes = datagramPacket.length
                    when (val decodeResult = AudioPacketCodec.decode(buffer, totalBytes)) {
                        is PacketDecodeResult.Success -> {
                            rxPackets.incrementAndGet()
                            rxBytes.addAndGet(totalBytes.toLong())

                            // 1. Record payload bytes immediately in exact order received
                            if (isCapturing) {
                                captureQueue?.offer(decodeResult.packet.pcmPayload)
                            }

                            // 2. Pass to existing speaker playback pipeline (unmodified)
                            processInOrder(decodeResult.packet)
                        }
                        is PacketDecodeResult.Error -> {
                            malformedPackets.incrementAndGet()
                        }
                    }
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    if (isActive && !newSocket.isClosed) {
                        malformedPackets.incrementAndGet()
                    }
                }
            }
        }
    }

    @Synchronized
    private fun processInOrder(packet: AudioPacket) {
        val seq = packet.sequenceNumber

        if (nextExpectedSeq == null) {
            // First packet received sets the initial sequence baseline
            nextExpectedSeq = (seq + 1) and 0xFFFFFFFFL
            onPcmReceived?.invoke(packet.pcmPayload)
            return
        }

        val expected = nextExpectedSeq!!

        if (seq == expected) {
            // In-order packet
            onPcmReceived?.invoke(packet.pcmPayload)
            var currentExpected = (expected + 1) and 0xFFFFFFFFL

            // Drain any contiguous buffered out-of-order packets
            while (reorderBuffer.containsKey(currentExpected)) {
                val bufferedPcm = reorderBuffer.remove(currentExpected)
                if (bufferedPcm != null) {
                    onPcmReceived?.invoke(bufferedPcm)
                }
                currentExpected = (currentExpected + 1) and 0xFFFFFFFFL
            }
            nextExpectedSeq = currentExpected
        } else if (AudioPacket.isSequenceAfter(expected, seq)) {
            // Future packet (out of order gap)
            val gap = AudioPacket.sequenceDistance(expected, seq)
            if (gap <= maxReorderBufferSize) {
                if (!reorderBuffer.containsKey(seq)) {
                    reorderBuffer[seq] = packet.pcmPayload
                    outOfOrder.incrementAndGet()
                } else {
                    duplicates.incrementAndGet()
                }
            } else {
                // Gap is too large to buffer; declare lost packets and advance
                sequenceGaps.addAndGet(gap)
                flushReorderBuffer()
                onPcmReceived?.invoke(packet.pcmPayload)
                nextExpectedSeq = (seq + 1) and 0xFFFFFFFFL
            }
        } else {
            // Past packet (duplicate or stale)
            duplicates.incrementAndGet()
        }
    }

    private fun flushReorderBuffer() {
        // Emit buffered packets in natural sequence order
        val sortedKeys = reorderBuffer.keys.sortedWith(Comparator { a, b ->
            if (a == b) 0
            else if (AudioPacket.isSequenceAfter(a, b)) 1
            else -1
        })
        for (k in sortedKeys) {
            val pcm = reorderBuffer.remove(k)
            if (pcm != null) {
                onPcmReceived?.invoke(pcm)
            }
        }
    }

    fun stop() {
        receiveJob?.cancel()
        receiveJob = null

        // Stop capturing diagnostic data and wait for writer thread to flush/close cleanly
        isCapturing = false
        captureWriterThread?.let { thread ->
            try {
                thread.join(3000)
            } catch (e: Exception) {
                // Interrupted
            }
        }
        captureWriterThread = null
        captureQueue = null

        val s = socket
        socket = null
        if (s != null && !s.isClosed) {
            try {
                s.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
        reorderBuffer.clear()
    }
}
