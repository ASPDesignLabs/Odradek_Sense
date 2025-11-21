package com.snakesan.neonflux

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

class FluxService : Service(), MessageClient.OnMessageReceivedListener {

    private lateinit var vibrator: Vibrator
    private val CHANNEL_ID = "FluxBackgroundChannel"

    override fun onCreate() {
        super.onCreate()

        // 1. Setup Vibrator with Attribution Tag (Fixes the error)
        // We create a specific context that is tied to the tag we declared in the Manifest
        val context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            createAttributionContext("flux_vibrations")
        } else {
            this
        }

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // 2. Register Bluetooth Listener
        Wearable.getMessageClient(this).addListener(this)

        // 3. Go Foreground immediately
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If the system kills us, restart us
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // We don't bind to activities, we just run.
    }

    // --- HAPTIC LOGIC ---
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/flux_sync") {
            val bytes = event.data
            if (bytes.size >= 3) {
                val profile = bytes[1].toInt()
                val intensity = bytes[2].toInt() / 100f
                playHaptics(profile, intensity)
            }
        }
    }

    private fun playHaptics(profile: Int, intensity: Float) {
        if (intensity <= 0.05f) return

        // CRITICAL: Use proper attributes so Doze mode doesn't block vibration
        val attributes = VibrationAttributes.Builder()
            .setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK)
            .build()

        try {
            when (profile) {
                0 -> { // DYNAMO
                    if (vibrator.hasAmplitudeControl()) {
                        val amp = (intensity * 255).toInt().coerceAtLeast(10)
                        vibrator.vibrate(VibrationEffect.createOneShot(40, amp), attributes)
                    } else {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), attributes)
                    }
                }
                1 -> { // GEIGER
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), attributes)
                }
                2 -> { // THROB
                    if (intensity > 0.4f) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK), attributes)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    // --- NOTIFICATION SETUP ---
    private fun startForegroundService() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Flux Connection",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeonFlux Active")
            .setContentText("Syncing haptics from watch...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Use generic icon
            .setContentIntent(pendingIntent)
            .setOngoing(true) // Can't be swiped away
            .build()

        startForeground(1, notification)
    }
}