package com.example.transport

import com.example.util.HostPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException

sealed interface TransportEvent {
    data class SocketOpened(val localPort: Int, val targetEndpoint: String) : TransportEvent
    data object SocketClosed : TransportEvent
    data class BytesSent(val bytesCount: Int) : TransportEvent
    data class BytesReceived(val data: ByteArray, val length: Int, val senderEndpoint: String) : TransportEvent
    data class Error(val message: String, val cause: Throwable? = null) : TransportEvent
}

class UdpTransport {

    private var socket: DatagramSocket? = null
    private var targetAddress: InetAddress? = null
    private var targetPort: Int = 0

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var receiveJob: Job? = null

    private var eventListener: ((TransportEvent) -> Unit)? = null

    @Synchronized
    fun isConnected(): Boolean {
        return socket?.let { !it.isClosed && it.isBound } ?: false
    }

    fun setEventListener(listener: (TransportEvent) -> Unit) {
        this.eventListener = listener
    }

    fun connect(hostPort: HostPort) {
        scope.launch {
            try {
                disconnectInternal()

                val address = InetAddress.getByName(hostPort.host)
                val newSocket = DatagramSocket().apply {
                    soTimeout = 0
                    reuseAddress = true
                }

                socket = newSocket
                targetAddress = address
                targetPort = hostPort.port

                val localPort = newSocket.localPort
                val endpointStr = "$hostPort"

                notifyEvent(TransportEvent.SocketOpened(localPort = localPort, targetEndpoint = endpointStr))

                startReceiveLoop(newSocket)
            } catch (e: Exception) {
                notifyEvent(TransportEvent.Error("Failed to open UDP socket: ${e.localizedMessage ?: e.message}", e))
            }
        }
    }

    fun sendBytes(data: ByteArray) {
        scope.launch {
            try {
                val currentSocket = socket
                val currentAddress = targetAddress
                val port = targetPort

                if (currentSocket == null || currentSocket.isClosed || currentAddress == null) {
                    notifyEvent(TransportEvent.Error("Cannot send bytes: socket is not open"))
                    return@launch
                }

                val datagramPacket = DatagramPacket(data, data.size, currentAddress, port)
                currentSocket.send(datagramPacket)
                notifyEvent(TransportEvent.BytesSent(data.size))
            } catch (e: Exception) {
                notifyEvent(TransportEvent.Error("Failed to send UDP packet: ${e.localizedMessage ?: e.message}", e))
            }
        }
    }

    fun disconnect() {
        scope.launch {
            disconnectInternal()
        }
    }

    private fun startReceiveLoop(activeSocket: DatagramSocket) {
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isActive && !activeSocket.isClosed) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    activeSocket.receive(packet)

                    val packetData = packet.data.copyOfRange(0, packet.length)
                    val senderStr = "${packet.address.hostAddress}:${packet.port}"

                    notifyEvent(
                        TransportEvent.BytesReceived(
                            data = packetData,
                            length = packet.length,
                            senderEndpoint = senderStr
                        )
                    )
                } catch (e: SocketException) {
                    break
                } catch (e: Exception) {
                    if (isActive && !activeSocket.isClosed) {
                        notifyEvent(TransportEvent.Error("Receive error: ${e.localizedMessage ?: e.message}", e))
                    }
                }
            }
        }
    }

    private suspend fun disconnectInternal() = withContext(Dispatchers.IO) {
        receiveJob?.cancel()
        receiveJob = null

        val currentSocket = socket
        socket = null
        targetAddress = null
        targetPort = 0

        if (currentSocket != null && !currentSocket.isClosed) {
            currentSocket.close()
            notifyEvent(TransportEvent.SocketClosed)
        }
    }

    private fun notifyEvent(event: TransportEvent) {
        eventListener?.invoke(event)
    }
}
