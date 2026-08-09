package com.example

import com.example.protocol.AudioPacket
import com.example.protocol.AudioPacketCodec
import com.example.protocol.PacketDecodeResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProtocolTest {

    @Test
    fun `test normal packet encode and decode`() {
        val pcmPayload = ByteArray(1152) { (it % 128).toByte() } // 144 stereo frames
        val originalPacket = AudioPacket(sequenceNumber = 12345L, pcmPayload = pcmPayload)

        val encodedBytes = AudioPacketCodec.encode(originalPacket)
        assertEquals(1160, encodedBytes.size) // 8 header + 1152 payload

        val decodeResult = AudioPacketCodec.decode(encodedBytes, encodedBytes.size)
        assertTrue(decodeResult is PacketDecodeResult.Success)

        val decodedPacket = (decodeResult as PacketDecodeResult.Success).packet
        assertEquals(12345L, decodedPacket.sequenceNumber)
        assertEquals(1152, decodedPacket.payloadLength)
        assertArrayEquals(pcmPayload, decodedPacket.pcmPayload)
    }

    @Test
    fun `test big-endian byte order for sequence and length`() {
        val pcmPayload = ByteArray(16) // 2 stereo frames
        val packet = AudioPacket(sequenceNumber = 0x01020304L, pcmPayload = pcmPayload)

        val bytes = AudioPacketCodec.encode(packet)
        // Sequence number bytes in big-endian: 0x01, 0x02, 0x03, 0x04
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
        assertEquals(0x03.toByte(), bytes[2])
        assertEquals(0x04.toByte(), bytes[3])

        // Payload length in big-endian: 16 -> 0x00, 0x00, 0x00, 0x10
        assertEquals(0x00.toByte(), bytes[4])
        assertEquals(0x00.toByte(), bytes[5])
        assertEquals(0x00.toByte(), bytes[6])
        assertEquals(0x10.toByte(), bytes[7])
    }

    @Test
    fun `test malformed header rejection when total length under 8 bytes`() {
        val shortBytes = ByteArray(7) { 0 }
        val decodeResult = AudioPacketCodec.decode(shortBytes, shortBytes.size)
        assertTrue(decodeResult is PacketDecodeResult.Error)
        val reason = (decodeResult as PacketDecodeResult.Error).reason
        assertTrue(reason.contains("Malformed header"))
    }

    @Test
    fun `test empty payload rejection`() {
        val headerWithZeroLen = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // seq 1
            0x00, 0x00, 0x00, 0x00  // len 0
        )
        val decodeResult = AudioPacketCodec.decode(headerWithZeroLen, headerWithZeroLen.size)
        assertTrue(decodeResult is PacketDecodeResult.Error)
        val reason = (decodeResult as PacketDecodeResult.Error).reason
        assertTrue(reason.contains("Empty or invalid payload"))
    }

    @Test
    fun `test payload exceeding max 1152 bytes rejection`() {
        val headerWithOverLen = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, // seq 1
            0x00, 0x00, 0x04, 0x88.toByte() // len 1160 (>1152)
        )
        val decodeResult = AudioPacketCodec.decode(headerWithOverLen, headerWithOverLen.size)
        assertTrue(decodeResult is PacketDecodeResult.Error)
        val reason = (decodeResult as PacketDecodeResult.Error).reason
        assertTrue(reason.contains("exceeds maximum 1152"))
    }

    @Test
    fun `test unaligned stereo frame payload rejection`() {
        // 10 bytes payload is not aligned to 8-byte boundary
        val bytes = ByteArray(8 + 10)
        bytes[0] = 0; bytes[1] = 0; bytes[2] = 0; bytes[3] = 1 // seq 1
        bytes[4] = 0; bytes[5] = 0; bytes[6] = 0; bytes[7] = 10 // len 10

        val decodeResult = AudioPacketCodec.decode(bytes, bytes.size)
        assertTrue(decodeResult is PacketDecodeResult.Error)
        val reason = (decodeResult as PacketDecodeResult.Error).reason
        assertTrue(reason.contains("not aligned to stereo float32 frame size"))
    }

    @Test
    fun `test uint32 sequence wraparound logic`() {
        val maxUint32 = AudioPacket.MAX_UINT32 // 4294967295

        // 0 comes strictly after maxUint32 in circular 32-bit sequence space
        assertTrue(AudioPacket.isSequenceAfter(maxUint32, 0L))
        assertEquals(1L, AudioPacket.sequenceDistance(maxUint32, 0L))

        // 5 comes strictly after maxUint32
        assertTrue(AudioPacket.isSequenceAfter(maxUint32, 5L))
        assertEquals(6L, AudioPacket.sequenceDistance(maxUint32, 5L))

        // maxUint32 is NOT after 0L
        assertFalse(AudioPacket.isSequenceAfter(0L, maxUint32))

        // 100 comes after 90
        assertTrue(AudioPacket.isSequenceAfter(90L, 100L))
        assertEquals(10L, AudioPacket.sequenceDistance(90L, 100L))
    }
}
