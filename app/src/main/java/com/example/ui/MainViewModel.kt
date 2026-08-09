package com.example.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import com.example.application.AudioTransportCoordinator
import com.example.model.AudioTransportStats
import com.example.model.LogEntry
import com.example.service.AudioBridgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel(
    val coordinator: AudioTransportCoordinator = AudioBridgeService.coordinator
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

    fun toggleTransport(context: Context) {
        if (isRunning.value) {
            val stopIntent = Intent(context, AudioBridgeService::class.java).apply {
                action = AudioBridgeService.ACTION_STOP
            }
            context.startService(stopIntent)
        } else {
            val ip = _pcIpInput.value.trim().ifBlank { "192.168.1.100" }
            val speakerPort = _speakerPortInput.value.toIntOrNull() ?: 5000
            val micPort = _micPortInput.value.toIntOrNull() ?: 5002

            val startIntent = Intent(context, AudioBridgeService::class.java).apply {
                action = AudioBridgeService.ACTION_START
                putExtra(AudioBridgeService.EXTRA_BACKEND_IP, ip)
                putExtra(AudioBridgeService.EXTRA_SPEAKER_PORT, speakerPort)
                putExtra(AudioBridgeService.EXTRA_MIC_PORT, micPort)
                putExtra(AudioBridgeService.EXTRA_IS_MIC_ENABLED, _isMicEnabled.value)
            }
            ContextCompat.startForegroundService(context, startIntent)
        }
    }

    fun clearLogs() {
        coordinator.clearLogs()
    }

    override fun onCleared() {
        super.onCleared()
        // Do not stop coordinator here: foreground service owns the bridge lifecycle.
    }
}
