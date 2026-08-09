package com.example.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong

class TcpSpeakerServer {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null

    var onPcmReceived: ((ByteArray) -> Unit)? = null
    var onLog: ((String) -> Unit)? = null

    val pcmChunksReceived = AtomicLong(0)
    val pcmBytesReceived = AtomicLong(0)

    @Volatile
    var isListening: Boolean = false
        private set

    @Volatile
    var isConnected: Boolean = false
        private set

    @Synchronized
    fun start(port: Int = 5000) {
        stop()

        pcmChunksReceived.set(0)
        pcmBytesReceived.set(0)

        serverJob = scope.launch {
            try {
                val sSocket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                serverSocket = sSocket
                isListening = true
                onLog?.invoke("Speaker TCP Server listening on port $port")

                while (isActive && !sSocket.isClosed) {
                    onLog?.invoke("Waiting for backend connection on TCP port $port...")
                    val socket = try {
                        sSocket.accept()
                    } catch (e: SocketException) {
                        break
                    } catch (e: Exception) {
                        break
                    }

                    clientSocket = socket
                    isConnected = true
                    val remoteAddr = socket.remoteSocketAddress
                    onLog?.invoke("Backend connected to Speaker TCP Server from $remoteAddr")

                    try {
                        socket.tcpNoDelay = true
                        val inputStream: InputStream = socket.getInputStream()

                        val readBuffer = ByteArray(16384)
                        var leftoverCount = 0

                        while (isActive && isConnected && !socket.isClosed) {
                            val bytesRead = inputStream.read(
                                readBuffer,
                                leftoverCount,
                                readBuffer.size - leftoverCount
                            )

                            if (bytesRead < 0) {
                                onLog?.invoke("Backend closed Speaker TCP connection")
                                break
                            }

                            val totalAvailable = leftoverCount + bytesRead
                            // Frame alignment: 8 bytes per stereo frame (4 bytes L float + 4 bytes R float)
                            val validFrameBytes = (totalAvailable / 8) * 8
                            leftoverCount = totalAvailable - validFrameBytes

                            if (validFrameBytes > 0) {
                                val pcmChunk = readBuffer.copyOfRange(0, validFrameBytes)
                                pcmChunksReceived.incrementAndGet()
                                pcmBytesReceived.addAndGet(validFrameBytes.toLong())
                                onPcmReceived?.invoke(pcmChunk)
                            }

                            if (leftoverCount > 0) {
                                System.arraycopy(
                                    readBuffer,
                                    validFrameBytes,
                                    readBuffer,
                                    0,
                                    leftoverCount
                                )
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            onLog?.invoke("Speaker TCP connection error: ${e.message}")
                        }
                    } finally {
                        isConnected = false
                        try {
                            socket.close()
                        } catch (ignored: Exception) {}
                        clientSocket = null
                        onLog?.invoke("Speaker TCP client disconnected")
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    onLog?.invoke("Failed to start Speaker TCP Server: ${e.message}")
                }
            } finally {
                isListening = false
            }
        }
    }

    @Synchronized
    fun stop() {
        isListening = false
        isConnected = false

        serverJob?.cancel()
        serverJob = null

        try {
            clientSocket?.close()
        } catch (ignored: Exception) {}
        clientSocket = null

        try {
            serverSocket?.close()
        } catch (ignored: Exception) {}
        serverSocket = null
    }
}
