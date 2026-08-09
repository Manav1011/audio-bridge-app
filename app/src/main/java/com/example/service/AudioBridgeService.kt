package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.application.AudioTransportCoordinator

class AudioBridgeService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): AudioBridgeService = this@AudioBridgeService
        fun getCoordinator(): AudioTransportCoordinator = coordinator
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        when (action) {
            ACTION_START -> {
                val ip = intent?.getStringExtra(EXTRA_BACKEND_IP) ?: "192.168.1.100"
                val speakerPort = intent?.getIntExtra(EXTRA_SPEAKER_PORT, 5000) ?: 5000
                val micPort = intent?.getIntExtra(EXTRA_MIC_PORT, 5002) ?: 5002
                val isMicEnabled = intent?.getBooleanExtra(EXTRA_IS_MIC_ENABLED, true) ?: true

                startForegroundServiceWithNotification(ip, speakerPort, micPort)
                coordinator.start(
                    backendIp = ip,
                    speakerPort = speakerPort,
                    micPort = micPort,
                    isMicEnabled = isMicEnabled
                )
            }
            ACTION_STOP -> {
                coordinator.stop()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification(ip: String, speakerPort: Int, micPort: Int) {
        val notification = buildNotification(ip, speakerPort, micPort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var serviceTypes = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceTypes = serviceTypes or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(NOTIFICATION_ID, notification, serviceTypes)
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(ip: String, speakerPort: Int, micPort: Int): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioBridgeService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStopIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PC Audio Bridge Active")
            .setContentText("Connected to $ip | Speaker :$speakerPort | Mic :$micPort")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingContentIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Bridge", pendingStopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Bridge Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification for PC Audio Bridge streaming service"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coordinator.stop()
        if (instance == this) {
            instance = null
        }
    }

    companion object {
        const val CHANNEL_ID = "audio_bridge_service_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_STOP = "com.example.service.ACTION_STOP"

        const val EXTRA_BACKEND_IP = "EXTRA_BACKEND_IP"
        const val EXTRA_SPEAKER_PORT = "EXTRA_SPEAKER_PORT"
        const val EXTRA_MIC_PORT = "EXTRA_MIC_PORT"
        const val EXTRA_IS_MIC_ENABLED = "EXTRA_IS_MIC_ENABLED"

        @Volatile
        var instance: AudioBridgeService? = null

        val coordinator: AudioTransportCoordinator = AudioTransportCoordinator()
    }
}
