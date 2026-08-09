package com.example.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class TcpAudioSenderTest {

    @Test
    fun `test arbitrary ByteArray transmission without modification`() {
        val sender = TcpAudioSender()
        val baos = ByteArrayOutputStream()

        val logs = mutableListOf<String>()
        sender.onLog = { logs.add(it) }

        sender.startWithOutputStream(baos)
        assertTrue(sender.isConnected)

        val testData = ByteArray(1024) { (it % 256).toByte() }
        sender.sendPcmData(testData)

        val sentBytes = baos.toByteArray()
        assertEquals(1024, sentBytes.size)
        assertArrayEquals(testData, sentBytes)
        assertEquals(1L, sender.pcmChunks.get())
        assertEquals(1024L, sender.pcmBytesSent.get())

        sender.stop()
        assertFalse(sender.isConnected)
    }

    @Test
    fun `test multiple writes reconstruct into exact concatenated byte stream`() {
        val sender = TcpAudioSender()
        val baos = ByteArrayOutputStream()

        sender.startWithOutputStream(baos)

        val chunk1 = ByteArray(512) { (it % 100).toByte() }
        val chunk2 = ByteArray(768) { ((it + 50) % 200).toByte() }
        val chunk3 = ByteArray(1024) { ((it * 3) % 256).toByte() }

        sender.sendPcmData(chunk1)
        sender.sendPcmData(chunk2)
        sender.sendPcmData(chunk3)

        val totalSentBytes = baos.toByteArray()
        val expectedConcat = chunk1 + chunk2 + chunk3

        assertEquals(expectedConcat.size, totalSentBytes.size)
        assertArrayEquals(expectedConcat, totalSentBytes)

        assertEquals(3L, sender.pcmChunks.get())
        assertEquals((512 + 768 + 1024).toLong(), sender.pcmBytesSent.get())

        sender.stop()
    }

    @Test
    fun `test stop logs statistics cleanly`() {
        val sender = TcpAudioSender()
        val baos = ByteArrayOutputStream()
        val logs = mutableListOf<String>()
        sender.onLog = { logs.add(it) }

        sender.startWithOutputStream(baos)
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        sender.sendPcmData(data)

        sender.stop()

        val completeLog = logs.findLast { it.contains("TCP MIC TEST COMPLETE") }
        assertTrue("Log should contain TCP MIC TEST COMPLETE", completeLog != null)
        assertTrue("Log should contain pcm_chunks=1", completeLog?.contains("pcm_chunks=1") == true)
        assertTrue("Log should contain pcm_bytes_sent=8", completeLog?.contains("pcm_bytes_sent=8") == true)
    }
}
