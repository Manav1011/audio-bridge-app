package com.example.model

data class AudioTransportStats(
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val rxPackets: Long = 0,
    val rxBytes: Long = 0,
    val sequenceGaps: Long = 0,
    val duplicates: Long = 0,
    val outOfOrder: Long = 0,
    val malformedPackets: Long = 0,
    val playbackUnderruns: Long = 0,
    val recorderOverruns: Long = 0,
    val isMicConnected: Boolean = false
)
