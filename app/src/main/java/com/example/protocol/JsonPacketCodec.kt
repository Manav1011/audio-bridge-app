package com.example.protocol

import org.json.JSONObject

class JsonPacketCodec : PacketCodec {
    override fun encode(packet: Packet): ByteArray {
        val json = JSONObject().apply {
            put("type", packet.type)
            put("payload", packet.payload)
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(data: ByteArray, length: Int): Packet? {
        if (length <= 0) return null
        val rawStr = String(data, 0, length, Charsets.UTF_8).trim()
        if (rawStr.isEmpty()) return null

        return try {
            val json = JSONObject(rawStr)
            val type = json.getInt("type")
            val payload = json.optJSONObject("payload") ?: JSONObject()
            Packet(type = type, payload = payload)
        } catch (e: Exception) {
            null
        }
    }
}

