package com.example.transport

import com.example.protocol.AudioPacket
import com.example.protocol.AudioPacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicLong

class UdpAudioSender {

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0

    private var nextSeqNumber: Long = 0L

    val txPackets = AtomicLong(0)
    val txBytes = AtomicLong(0)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Synchronized
    fun isConnected(): Boolean {
        return socket?.let { !it.isClosed && it.isBound } ?: false
    }

    fun start(pcIp: String, txPort: Int) {
        stop()
        try {
            val address = InetAddress.getByName(pcIp)
            val newSocket = DatagramSocket()
            socket = newSocket
            targetAddress = address
            targetPort = txPort
            nextSeqNumber = 0L
            txPackets.set(0)
            txBytes.set(0)
        } catch (e: Exception) {
            throw e
        }
    }

    fun sendPcmData(pcmData: ByteArray) {
        require(pcmData.size % 8 == 0) {
            "PCM payload length (${pcmData.size}) must be divisible by 8 bytes (stereo Float32 LE frames)"
        }
        val currentSocket = socket ?: return
        val currentAddress = targetAddress ?: return
        val port = targetPort
        if (currentSocket.isClosed) return

        scope.launch {
            try {
                var offset = 0
                val totalLength = pcmData.size

                while (offset < totalLength) {
                    val remaining = totalLength - offset
                    var chunkSize = Math.min(remaining, AudioPacket.MAX_PAYLOAD_SIZE)

                    // Ensure payload chunk is aligned to 8-byte stereo float32 frame boundary
                    chunkSize = (chunkSize / AudioPacket.FRAME_SIZE_BYTES) * AudioPacket.FRAME_SIZE_BYTES
                    if (chunkSize <= 0) break

                    val payload = ByteArray(chunkSize)
                    System.arraycopy(pcmData, offset, payload, 0, chunkSize)

                    val seq = nextSeqNumber
                    nextSeqNumber = (nextSeqNumber + 1) and 0xFFFFFFFFL

                    val packetObj = AudioPacket(sequenceNumber = seq, pcmPayload = payload)
                    val encodedDatagramBytes = AudioPacketCodec.encode(packetObj)

                    val datagramPacket = DatagramPacket(
                        encodedDatagramBytes,
                        encodedDatagramBytes.size,
                        currentAddress,
                        port
                    )
                    currentSocket.send(datagramPacket)

                    txPackets.incrementAndGet()
                    txBytes.addAndGet(encodedDatagramBytes.size.toLong())

                    offset += chunkSize
                }
            } catch (e: Exception) {
                // Ignore socket closed during shutdown
            }
        }
    }

    fun stop() {
        val s = socket
        socket = null
        targetAddress = null
        targetPort = 0
        if (s != null && !s.isClosed) {
            try {
                s.close()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
