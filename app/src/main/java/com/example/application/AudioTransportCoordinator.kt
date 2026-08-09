package com.example.application

import com.example.model.AudioTransportStats
import com.example.model.LogEntry
import com.example.model.LogType
import com.example.transport.TcpAudioSender
import com.example.transport.TcpSpeakerServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class AudioTransportCoordinator(
    private val tcpSpeakerServer: TcpSpeakerServer = TcpSpeakerServer(),
    private val player: AudioPlayer = AudioPlayer(),
    private val recorder: AudioRecorder = AudioRecorder(),
    private val tcpSender: TcpAudioSender = TcpAudioSender()
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _stats = MutableStateFlow(AudioTransportStats())
    val stats: StateFlow<AudioTransportStats> = _stats.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    val lastSavedCaptureNotice = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null

    init {
        // Speaker path: TCP Server -> Audio Player
        tcpSpeakerServer.onPcmReceived = { pcm ->
            player.submitPcm(pcm)
        }

        tcpSpeakerServer.onLog = { msg ->
            addLog(msg, LogType.SYSTEM)
        }

        // Microphone path: Audio Recorder -> TCP Sender (Backend :5002)
        recorder.onPcmCaptured = { pcm ->
            tcpSender.sendPcmData(pcm)
        }

        recorder.onDiagnosticLog = { msg ->
            addLog(msg, LogType.SYSTEM)
        }

        tcpSender.onLog = { msg ->
            addLog(msg, LogType.SYSTEM)
        }

        addLog("AudioTransportCoordinator initialized. Final Architecture: Microphone (TCP :5002) | Speaker (TCP Server :5000)", LogType.SYSTEM)
    }

    @Synchronized
    fun start(backendIp: String, speakerPort: Int = 5000, micPort: Int = 5002, isMicEnabled: Boolean = true, outputDir: java.io.File? = null) {
        if (_isRunning.value) {
            stop()
        }

        try {
            lastSavedCaptureNotice.value = null
            addLog("Starting Audio Bridge... Backend IP: $backendIp", LogType.SYSTEM)
            addLog("Speaker path: TCP Server Port $speakerPort | Microphone path: TCP Port $micPort", LogType.SYSTEM)

            // Start Speaker TCP server & player
            tcpSpeakerServer.start(speakerPort)
            player.start()
            addLog("Speaker TCP Server listening on port $speakerPort. Playback ready.", LogType.SYSTEM)

            // Start Microphone capture & TCP sender if enabled
            if (isMicEnabled) {
                tcpSender.start(backendIp, micPort)
                recorder.start(useTestTone = false, outputDir = outputDir)
                val formatDesc = recorder.actualCaptureFormatDescription
                addLog("Microphone capture active (TCP Port $micPort): $formatDesc", LogType.SYSTEM)
            } else {
                addLog("Microphone is Muted (OFF). Speaker TCP Server active.", LogType.SYSTEM)
            }

            _isRunning.value = true

            // Start stats polling loop
            statsJob?.cancel()
            statsJob = scope.launch {
                while (_isRunning.value) {
                    updateStats()
                    delay(250)
                }
            }
        } catch (e: Exception) {
            stop()
            addLog("Failed to start audio bridge: ${e.localizedMessage ?: e.message}", LogType.ERROR)
        }
    }

    @Synchronized
    fun stop() {
        if (!_isRunning.value && statsJob == null) {
            return
        }

        addLog("Stopping Audio Bridge...", LogType.SYSTEM)
        _isRunning.value = false
        statsJob?.cancel()
        statsJob = null

        tcpSender.stop()
        recorder.stop()
        tcpSpeakerServer.stop()
        player.stop()

        updateStats()

        addLog("Audio Bridge stopped cleanly.", LogType.SYSTEM)
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("Console logs cleared.", LogType.SYSTEM)
    }

    private fun updateStats() {
        _stats.value = AudioTransportStats(
            txPackets = tcpSender.pcmChunks.get(),
            txBytes = tcpSender.pcmBytesSent.get(),
            rxPackets = tcpSpeakerServer.pcmChunksReceived.get(),
            rxBytes = tcpSpeakerServer.pcmBytesReceived.get(),
            playbackUnderruns = player.playbackUnderruns.get(),
            recorderOverruns = recorder.recorderOverruns.get(),
            isMicConnected = tcpSender.isConnected,
            isSpeakerConnected = tcpSpeakerServer.isConnected,
            isSpeakerListening = tcpSpeakerServer.isListening
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.US, "%.1f MB", mb)
    }

    private fun addLog(message: String, type: LogType) {
        _logs.update { current ->
            current + LogEntry(message = message, type = type)
        }
    }
}
