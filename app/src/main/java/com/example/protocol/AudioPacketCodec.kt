package com.example.protocol

sealed class PacketDecodeResult {
    data class Success(val packet: AudioPacket) : PacketDecodeResult()
    data class Error(val reason: String) : PacketDecodeResult()
}

object AudioPacketCodec {

    /**
     * Encodes an AudioPacket into an 8-byte header + PCM payload ByteArray.
     * Header format:
     * - bytes 0-3: uint32 sequence number (big-endian)
     * - bytes 4-7: uint32 payload length (big-endian)
     */
    fun encode(packet: AudioPacket): ByteArray {
        val payloadSize = packet.pcmPayload.size
        require(payloadSize <= AudioPacket.MAX_PAYLOAD_SIZE) {
            "Payload size $payloadSize exceeds maximum allowable $AudioPacket.MAX_PAYLOAD_SIZE bytes"
        }
        require(payloadSize % AudioPacket.FRAME_SIZE_BYTES == 0) {
            "Payload size $payloadSize is not aligned to stereo float32 frame boundary (8 bytes)"
        }

        val out = ByteArray(AudioPacket.HEADER_SIZE + payloadSize)
        val seq = packet.sequenceNumber and 0xFFFFFFFFL

        // Sequence number (uint32 big-endian)
        out[0] = ((seq shr 24) and 0xFF).toByte()
        out[1] = ((seq shr 16) and 0xFF).toByte()
        out[2] = ((seq shr 8) and 0xFF).toByte()
        out[3] = (seq and 0xFF).toByte()

        // Payload length (uint32 big-endian)
        val len = payloadSize.toLong() and 0xFFFFFFFFL
        out[4] = ((len shr 24) and 0xFF).toByte()
        out[5] = ((len shr 16) and 0xFF).toByte()
        out[6] = ((len shr 8) and 0xFF).toByte()
        out[7] = (len and 0xFF).toByte()

        // Copy PCM payload
        System.arraycopy(packet.pcmPayload, 0, out, AudioPacket.HEADER_SIZE, payloadSize)
        return out
    }

    /**
     * Decodes a raw UDP datagram byte array into an AudioPacket.
     * Validates header, payload bounds, frame alignment, and length matching.
     */
    fun decode(data: ByteArray, length: Int): PacketDecodeResult {
        if (length < AudioPacket.HEADER_SIZE) {
            return PacketDecodeResult.Error("Malformed header: Received $length bytes, minimum header is 8 bytes")
        }

        // Parse sequence_number (uint32 big-endian)
        val seq0 = (data[0].toInt() and 0xFF).toLong()
        val seq1 = (data[1].toInt() and 0xFF).toLong()
        val seq2 = (data[2].toInt() and 0xFF).toLong()
        val seq3 = (data[3].toInt() and 0xFF).toLong()
        val sequenceNumber = (seq0 shl 24) or (seq1 shl 16) or (seq2 shl 8) or seq3

        // Parse payload_length (uint32 big-endian)
        val len0 = (data[4].toInt() and 0xFF).toLong()
        val len1 = (data[5].toInt() and 0xFF).toLong()
        val len2 = (data[6].toInt() and 0xFF).toLong()
        val len3 = (data[7].toInt() and 0xFF).toLong()
        val payloadLengthLong = (len0 shl 24) or (len1 shl 16) or (len2 shl 8) or len3

        if (payloadLengthLong > AudioPacket.MAX_PAYLOAD_SIZE) {
            return PacketDecodeResult.Error("Payload size $payloadLengthLong exceeds maximum 1152 bytes")
        }

        val payloadLength = payloadLengthLong.toInt()
        if (payloadLength <= 0) {
            return PacketDecodeResult.Error("Empty or invalid payload length ($payloadLength bytes)")
        }

        if (length < AudioPacket.HEADER_SIZE + payloadLength) {
            return PacketDecodeResult.Error("Truncated datagram: Header specifies $payloadLength payload bytes, but total received is $length bytes")
        }

        if (payloadLength % AudioPacket.FRAME_SIZE_BYTES != 0) {
            return PacketDecodeResult.Error("Payload length $payloadLength is not aligned to stereo float32 frame size (8 bytes)")
        }

        val payload = ByteArray(payloadLength)
        System.arraycopy(data, AudioPacket.HEADER_SIZE, payload, 0, payloadLength)

        return PacketDecodeResult.Success(AudioPacket(sequenceNumber, payload))
    }
}
