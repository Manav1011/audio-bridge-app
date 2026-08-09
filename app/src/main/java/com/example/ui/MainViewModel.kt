package com.example.ui

import androidx.lifecycle.ViewModel
import com.example.application.AudioTransportCoordinator
import com.example.model.AudioTransportStats
import com.example.model.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(
    val coordinator: AudioTransportCoordinator = AudioTransportCoordinator()
) : ViewModel() {

    private val _pcIpInput = MutableStateFlow("192.168.1.100")
    val pcIpInput: StateFlow<String> = _pcIpInput.asStateFlow()

    private val _speakerPortInput = MutableStateFlow("5000")
    val speakerPortInput: StateFlow<String> = _speakerPortInput.asStateFlow()

    private val _micPortInput = MutableStateFlow("5002")
    val micPortInput: StateFlow<String> = _micPortInput.asStateFlow()

    private val _isMicEnabled = MutableStateFlow(true)
    val isMicEnabled: StateFlow<Boolean> = _isMicEnabled.asStateFlow()

    val isRunning: StateFlow<Boolean> = coordinator.isRunning
    val stats: StateFlow<AudioTransportStats> = coordinator.stats
    val logs: StateFlow<List<LogEntry>> = coordinator.logs
    val lastSavedCaptureNotice: StateFlow<String?> = coordinator.lastSavedCaptureNotice

    fun onPcIpChange(newIp: String) {
        _pcIpInput.value = newIp.trim()
    }

    fun onSpeakerPortChange(newPort: String) {
        _speakerPortInput.value = newPort.filter { it.isDigit() }
    }

    fun onMicPortChange(newPort: String) {
        _micPortInput.value = newPort.filter { it.isDigit() }
    }

    fun onMicEnabledToggle(enabled: Boolean) {
        _isMicEnabled.value = enabled
    }

    fun toggleTransport(outputDir: java.io.File? = null) {
        if (isRunning.value) {
            coordinator.stop()
        } else {
            val ip = _pcIpInput.value.trim().ifBlank { "192.168.1.100" }
            val speakerPort = _speakerPortInput.value.toIntOrNull() ?: 5000
            val micPort = _micPortInput.value.toIntOrNull() ?: 5002
            coordinator.start(
                backendIp = ip,
                speakerPort = speakerPort,
                micPort = micPort,
                isMicEnabled = _isMicEnabled.value,
                outputDir = outputDir
            )
        }
    }

    fun clearLogs() {
        coordinator.clearLogs()
    }

    override fun onCleared() {
        super.onCleared()
        coordinator.stop()
    }
}
