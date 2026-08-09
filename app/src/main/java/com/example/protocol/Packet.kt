package com.example.protocol

import org.json.JSONObject

data class Packet(
    val type: Int,
    val payload: JSONObject = JSONObject()
) {
    val typeName: String
        get() = when (type) {
            TYPE_HELLO -> "HELLO"
            TYPE_HELLO_ACK -> "HELLO_ACK"
            TYPE_PING -> "PING"
            TYPE_PONG -> "PONG"
            TYPE_MESSAGE -> "MESSAGE"
            else -> "UNKNOWN($type)"
        }

    val textPayload: String?
        get() = when {
            payload.has("text") -> payload.optString("text")
            payload.has("client") -> payload.optString("client")
            payload.length() > 0 -> payload.toString()
            else -> null
        }

    companion object {
        const val TYPE_HELLO = 1
        const val TYPE_HELLO_ACK = 2
        const val TYPE_PING = 3
        const val TYPE_PONG = 4
        const val TYPE_MESSAGE = 5

        fun hello(clientInfo: String = "Android-AudioBridge-V1"): Packet =
            Packet(
                type = TYPE_HELLO,
                payload = JSONObject().apply { put("client", clientInfo) }
            )

        fun helloAck(): Packet =
            Packet(type = TYPE_HELLO_ACK, payload = JSONObject())

        fun ping(): Packet =
            Packet(type = TYPE_PING, payload = JSONObject())

        fun pong(): Packet =
            Packet(type = TYPE_PONG, payload = JSONObject())

        fun message(text: String): Packet =
            Packet(
                type = TYPE_MESSAGE,
                payload = JSONObject().apply { put("text", text) }
            )
    }
}

interface PacketCodec {
    fun encode(packet: Packet): ByteArray
    fun decode(data: ByteArray, length: Int): Packet?
}

