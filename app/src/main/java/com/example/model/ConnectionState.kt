package com.example.model

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class LogType {
    SYSTEM,
    SENT,
    RECEIVED,
    ERROR
}

data class LogEntry(
    val id: Long = System.nanoTime(),
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String,
    val type: LogType,
    val rawPacket: String? = null
)
