package com.example.application

import com.example.model.ConnectionState
import com.example.model.LogEntry
import com.example.model.LogType
import com.example.protocol.JsonPacketCodec
import com.example.protocol.Packet
import com.example.protocol.PacketCodec
import com.example.transport.TransportEvent
import com.example.transport.UdpTransport
import com.example.util.EndpointParser
import com.example.util.HostPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AudioBridgeClient(
    private val transport: UdpTransport = UdpTransport(),
    private val codec: PacketCodec = JsonPacketCodec()
) {
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    init {
        transport.setEventListener { event ->
            handleTransportEvent(event)
        }
        addLog("AudioBridgeClient initialized. Protocol layer active.", LogType.SYSTEM)
    }

    fun isConnected(): Boolean = transport.isConnected()

    fun connect(endpointStr: String) {
        val parsed = EndpointParser.parse(endpointStr)
        parsed.fold(
            onSuccess = { hostPort ->
                _connectionState.value = ConnectionState.CONNECTING
                addLog("Connecting transport to $hostPort...", LogType.SYSTEM)
                transport.connect(hostPort)
            },
            onFailure = { error ->
                _connectionState.value = ConnectionState.ERROR
                addLog("Connection failure: ${error.message}", LogType.ERROR)
            }
        )
    }

    fun disconnect() {
        addLog("Disconnecting transport...", LogType.SYSTEM)
        transport.disconnect()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendHello() {
        sendPacket(Packet.hello("Android-AudioBridge-V1"), "HELLO")
    }

    fun sendPing() {
        sendPacket(Packet.ping(), "PING")
    }

    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            addLog("Cannot send empty message", LogType.ERROR)
            return
        }
        sendPacket(Packet.message(trimmed), "MESSAGE")
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Logs cleared.", LogType.SYSTEM)
    }

    private fun sendPacket(packet: Packet, packetName: String) {
        if (!transport.isConnected()) {
            addLog("Cannot send $packetName: Transport is disconnected", LogType.ERROR)
            return
        }

        try {
            val encodedBytes = codec.encode(packet)
            transport.sendBytes(encodedBytes)
            val detail = packet.textPayload?.let { " - \"$it\"" } ?: ""
            addLog(
                message = "Packet sent: ${packet.typeName}$detail (${encodedBytes.size} bytes)",
                type = LogType.SENT,
                rawPacket = String(encodedBytes, Charsets.UTF_8)
            )
        } catch (e: Exception) {
            addLog("Failed to encode/send $packetName: ${e.localizedMessage ?: e.message}", LogType.ERROR)
        }
    }

    private fun handleTransportEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.SocketOpened -> {
                addLog("Socket opened on local port ${event.localPort} -> ${event.targetEndpoint}", LogType.SYSTEM)
                // Protocol state behavior: Automatically send HELLO upon socket opening
                sendHello()
            }

            is TransportEvent.SocketClosed -> {
                _connectionState.value = ConnectionState.DISCONNECTED
                addLog("Socket closed", LogType.SYSTEM)
            }

            is TransportEvent.BytesSent -> {
                // Handled in sendPacket for logging
            }

            is TransportEvent.BytesReceived -> {
                val rawStr = String(event.data, 0, event.length, Charsets.UTF_8)
                val packet = codec.decode(event.data, event.length)
                if (packet != null) {
                    val payloadStr = packet.textPayload?.let { " - \"$it\"" } ?: ""
                    addLog(
                        message = "Packet received: ${packet.typeName}$payloadStr (${event.length} bytes from ${event.senderEndpoint})",
                        type = LogType.RECEIVED,
                        rawPacket = rawStr
                    )

                    // Application protocol logic
                    when (packet.type) {
                        Packet.TYPE_HELLO_ACK -> {
                            _connectionState.value = ConnectionState.CONNECTED
                            addLog("Connection success: HELLO_ACK received from backend!", LogType.SYSTEM)
                        }
                        Packet.TYPE_PING -> {
                            addLog("Auto-responding to PING with PONG...", LogType.SYSTEM)
                            sendPacket(Packet.pong(), "PONG")
                        }
                        Packet.TYPE_PONG -> {
                            addLog("PONG response acknowledged from backend", LogType.SYSTEM)
                        }
                        Packet.TYPE_HELLO -> {
                            _connectionState.value = ConnectionState.CONNECTED
                            addLog("Received HELLO from server, sending HELLO_ACK...", LogType.SYSTEM)
                            sendPacket(Packet.helloAck(), "HELLO_ACK")
                        }
                    }
                } else {
                    addLog("Packet received: Unknown format (${event.length} bytes from ${event.senderEndpoint}): $rawStr", LogType.RECEIVED)
                }
            }

            is TransportEvent.Error -> {
                _connectionState.value = ConnectionState.ERROR
                addLog("Connection failure: ${event.message}", LogType.ERROR)
            }
        }
    }

    private fun addLog(message: String, type: LogType, rawPacket: String? = null) {
        _logs.update { current ->
            current + LogEntry(message = message, type = type, rawPacket = rawPacket)
        }
    }
}
