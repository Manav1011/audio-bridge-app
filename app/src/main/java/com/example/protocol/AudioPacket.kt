package com.example.protocol

/**
 * Binary UDP Audio Packet specification for PC <-> Android Audio Transport Phase 1.
 *
 * Header (8 bytes):
 * - bytes 0-3: sequence_number (uint32, big-endian)
 * - bytes 4-7: payload_length (uint32, big-endian)
 *
 * Payload:
 * - payload_length bytes of 48 kHz stereo float32 little-endian raw PCM.
 * - Max payload length = 1152 bytes (144 stereo frames).
 * - Must be a multiple of 8 bytes (1 stereo float32 frame = 8 bytes).
 */
data class AudioPacket(
    val sequenceNumber: Long, // Unsigned 32-bit integer stored in Long (0..4294967295)
    val pcmPayload: ByteArray
) {
    val payloadLength: Int = pcmPayload.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioPacket) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (!pcmPayload.contentEquals(other.pcmPayload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sequenceNumber.hashCode()
        result = 31 * result + pcmPayload.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 8
        const val MAX_PAYLOAD_SIZE = 1152
        const val MAX_PACKET_SIZE = HEADER_SIZE + MAX_PAYLOAD_SIZE // 1160 bytes
        const val FRAME_SIZE_BYTES = 8 // Stereo float32 (2 channels * 4 bytes)
        const val MAX_UINT32 = 4294967295L

        /**
         * Helper to compare unsigned 32-bit sequence numbers with wraparound support.
         * Returns true if [seqB] comes strictly after [seqA] in circular sequence space.
         */
        fun isSequenceAfter(seqA: Long, seqB: Long): Boolean {
            if (seqA == seqB) return false
            val diff = (seqB - seqA) and 0xFFFFFFFFL
            return diff < 2147483648L
        }

        /**
         * Calculates the sequence distance (gap) from [seqA] to [seqB] handling uint32 wraparound.
         */
        fun sequenceDistance(seqA: Long, seqB: Long): Long {
            return (seqB - seqA) and 0xFFFFFFFFL
        }
    }
}
