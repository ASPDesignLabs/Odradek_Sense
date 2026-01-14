package com.snakesan.neonflux

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.nio.ByteBuffer

class FluxService : Service(), MessageClient.OnMessageReceivedListener {

    private lateinit var vibrator: Vibrator
    private var clinicalJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null 

    override fun onCreate() {
        super.onCreate()
        val ctx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) createAttributionContext("flux_vibrations") else this
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NeonFlux:MetronomeLock")
        wakeLock?.acquire(60*60*1000L) 
        
        Wearable.getMessageClient(this).addListener(this)
        startForegroundService()
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
        clinicalJob?.cancel()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/flux_sync") {
            val bytes = event.data
            // Reactor Mode Sync
            if (bytes[0].toInt() == 3 || bytes[0].toInt() == 1) { 
                if (clinicalJob?.isActive == true) clinicalJob?.cancel()
                
                playOneShot(bytes[1].toInt(), bytes[2].toInt() / 100f)
                broadcastBeat()
            }
        } else if (event.path == "/clinical_start") {
            // Clinical Mode Start
            try {
                val buffer = ByteBuffer.wrap(event.data)
                val profile = buffer.get().toInt()
                val bpm = buffer.getInt()
                val intensity = buffer.get().toInt()
                val sleep = buffer.get().toInt() == 1
                val targetTime = buffer.getLong()
                startPrecisionMetronome(profile, bpm, intensity, targetTime)
            } catch (e: Exception) { e.printStackTrace() }
        } else if (event.path == "/clinical_stop") {
            clinicalJob?.cancel()
        }
    }

    private fun startPrecisionMetronome(profile: Int, bpm: Int, intensityInt: Int, startTime: Long) {
        clinicalJob?.cancel()
        clinicalJob = scope.launch {
            val periodMs = 60000.0 / bpm
            val intensity = intensityInt / 100f
            
            val wait = startTime - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            
            var beatCount = 0L
            
            while(isActive) {
                // 1. Play Physical
                playOneShot(profile, intensity)
                
                // 2. Broadcast Visual to UI
                broadcastBeat()

                beatCount++
                
                // 3. Timing Calc
                val nextBeatTime = startTime + (beatCount * periodMs).toLong()
                val sleepTime = nextBeatTime - System.currentTimeMillis()
                
                if (sleepTime > 0) delay(sleepTime)
            }
        }
    }

    private fun broadcastBeat() {
        // FIX: Must set package to communicate with RECEIVER_NOT_EXPORTED in Activity
        val intent = Intent("com.snakesan.neonflux.BEAT_EVENT")
        intent.setPackage(packageName) 
        sendBroadcast(intent)
    }

    private fun playOneShot(profile: Int, intensity: Float) {
        if (intensity <= 0.05f) return
        val attrs = VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK).build()
        val amp = (intensity * 255).toInt().coerceAtLeast(10)

        if (vibrator.hasAmplitudeControl()) {
            when (profile) {
                0 -> vibrator.vibrate(VibrationEffect.createOneShot(40, amp), attrs)
                1 -> vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK), attrs)
                2 -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 100), intArrayOf(0, amp/2, 0, amp), -1), attrs)
            }
        } else {
            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK), attrs)
        }
    }

    private fun startForegroundService() {
        val channel = NotificationChannel("FluxBackgroundChannel", "Flux Service", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        startForeground(1, NotificationCompat.Builder(this, "FluxBackgroundChannel")
            .setContentTitle("NeonFlux Active").setSmallIcon(android.R.drawable.ic_dialog_info).build())
    }
}
