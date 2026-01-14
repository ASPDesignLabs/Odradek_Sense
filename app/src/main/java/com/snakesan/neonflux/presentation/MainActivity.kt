package com.snakesan.neonflux

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.*
import kotlin.random.Random

// --- ENUMS ---
enum class FluxState { MONITOR, ACTIVE }
enum class Deck { REACTOR, CLINICAL }

// --- THEME CONSTANTS ---
val FluxCyan = Color(0xFF00F3FF)
val FluxPink = Color(0xFFFF0055)
val FluxDark = Color(0xFF121212)
val FluxBg = Color(0xFF050505)

// --- LOGGING HELPER ---
fun logPulse(mode: Int, intensity: Int) {
    Log.d("NEON_PWR", "${System.currentTimeMillis()},99,$mode,$intensity,0,0")
}

// --- SHARED VIBRATION HELPER (Top Level) ---
fun vibrateAck(context: Context) {
    val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    } else {
        v.vibrate(100)
    }
}

// Overload for when we already have the vibrator instance (for loop performance)
fun vibrateAck(v: Vibrator) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
    } else {
        v.vibrate(100)
    }
}

class MainActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private lateinit var prefs: SharedPreferences

    // Clinical Config (Persistent)
    var clinicalProfile by mutableIntStateOf(0)
    var clinicalBpm by mutableIntStateOf(60)
    var clinicalIntensity by mutableIntStateOf(50)
    var clinicalSleep by mutableStateOf(false)

    // Reactor Profile (Persistent)
    var reactorProfile by mutableIntStateOf(0)
    
    // VISUAL STATE: Triggered by incoming messages
    var isSyncing by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("FluxWatchConfig", Context.MODE_PRIVATE)
        
        // Restore State
        clinicalProfile = prefs.getInt("profile", 0)
        clinicalBpm = prefs.getInt("bpm", 60)
        clinicalIntensity = prefs.getInt("intensity", 50)
        clinicalSleep = prefs.getBoolean("sleep", false)
        reactorProfile = prefs.getInt("reactor_profile", 0)

        Wearable.getMessageClient(this).addListener(this)
        setContent { MaterialTheme { OdradekSense(this) } }
    }

    override fun onDestroy() {
        super.onDestroy()
        Wearable.getMessageClient(this).removeListener(this)
    }

    fun saveReactorProfile(profile: Int) {
        reactorProfile = profile
        prefs.edit().putInt("reactor_profile", profile).apply()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == "/clinical_conf") {
            try {
                val buffer = ByteBuffer.wrap(event.data)
                clinicalProfile = buffer.get().toInt()
                clinicalBpm = buffer.getInt()
                clinicalIntensity = buffer.get().toInt()
                clinicalSleep = buffer.get().toInt() == 1
                
                prefs.edit().apply {
                    putInt("profile", clinicalProfile)
                    putInt("bpm", clinicalBpm)
                    putInt("intensity", clinicalIntensity)
                    putBoolean("sleep", clinicalSleep)
                    apply()
                }

                // 1. Acknowledge reception physically
                vibrateAck(this)
                
                // 2. Trigger Visual Sync Sequence (Handled in Compose)
                isSyncing = true
                
            } catch (e: Exception) { Log.e("Flux", "Config Error", e) }
        }
    }
}

class SynthEngine {
    init { try { System.loadLibrary("neonflux") } catch (e: Exception) {} }
    
    var frequency = 440.0
    var amplitude = 0.0
    var isStandby = true 

    private external fun startNative()
    private external fun stopNative()
    private external fun updateNative(freq: Float, amp: Float)
    private external fun setVolumeNative(vol: Float)
    private external fun pauseSensorsNative(paused: Boolean)
    external fun getSensorMagnitude(): Float 
    
    fun start() { startNative(); pauseSensorsNative(false) }
    fun stop() { stopNative() }
    
    fun update() {
        val targetAmp = if (isStandby) 0.0f else amplitude.toFloat()
        updateNative(frequency.toFloat(), targetAmp)
    }
    
    fun setVolume(vol: Float) { setVolumeNative(vol) }
}

// --- UI COMPONENTS ---

@Composable
fun FluxButton(text: String, onClick: () -> Unit, color: Color = FluxCyan, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(35.dp)
            .clip(CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .background(color.copy(alpha = 0.2f))
            .border(1.dp, color.copy(alpha = 0.5f), CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
fun FluxLabel(title: String, value: String, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        Text(value, color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OdradekSense(activity: MainActivity) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val vibrator = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    var currentDeck by remember { mutableStateOf(Deck.REACTOR) }
    var showExitDialog by remember { mutableStateOf(false) }
    var profileNameToast by remember { mutableStateOf("") }
    
    var batteryLevel by remember { mutableIntStateOf(100) }
    var timeRemaining by remember { mutableStateOf("CALC...") }

    var isAudioMode by remember { mutableStateOf(false) }
    var motionMagnitude by remember { mutableFloatStateOf(0f) }
    var fluxState by remember { mutableStateOf(FluxState.MONITOR) }
    
    var isClinicalActive by remember { mutableStateOf(false) }
    var countdownValue by remember { mutableIntStateOf(0) }
    
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }

    val synth = remember { SynthEngine() }
    
    // Caching & Power Optimizations
    var cachedNodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    LaunchedEffect(Unit) {
        while(isActive) {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { cachedNodes = it }
            delay(10000) 
        }
    }

    val isActiveSession = (fluxState == FluxState.ACTIVE) || isClinicalActive
    val window = (context as? Activity)?.window
    DisposableEffect(isActiveSession) {
        if (window != null) {
            if (isActiveSession) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Slow Logger
    LaunchedEffect(isClinicalActive, isAudioMode, fluxState) {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        Log.d("NEON_PWR", "Time,State,Mode,MicroAmps,Voltage,Level")
        while(isActive) {
            val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            batteryLevel = lvl
            Log.d("NEON_PWR", "${System.currentTimeMillis()},0,0,$ua,0,$lvl")
            val burnRate = if(isClinicalActive) 0.3 else if (isAudioMode) 0.8 else 0.5 
            val minsLeft = (lvl / burnRate).toInt()
            timeRemaining = "${minsLeft / 60}h ${minsLeft % 60}m"
            delay(5000)
        }
    }

    // Toast Timer
    LaunchedEffect(profileNameToast) {
        if (profileNameToast.isNotEmpty()) { delay(1500); profileNameToast = "" }
    }

    val isLockedDown = isClinicalActive && countdownValue == 0
    val shouldDarken = isLockedDown || (fluxState == FluxState.ACTIVE && currentDeck == Deck.REACTOR && !showExitDialog)
    val curtainAlpha by animateFloatAsState(targetValue = if (shouldDarken) 1f else 0f, animationSpec = tween(800))

    BackHandler(enabled = !showExitDialog && !isLockedDown) { showExitDialog = true }

    // Engine Lifecycle
    val sensorsEnabled = (currentDeck == Deck.REACTOR && !isClinicalActive)
    DisposableEffect(sensorsEnabled) {
        if (sensorsEnabled) synth.start() else synth.stop()
        onDispose { synth.stop() }
    }

    // REACTOR LOOP
    LaunchedEffect(sensorsEnabled, fluxState, isAudioMode, activity.reactorProfile) {
        var lastNetSend = 0L
        synth.isStandby = true; synth.update()

        while(isActive && sensorsEnabled) {
            val rawMag = synth.getSensorMagnitude()
            if (rawMag > 1.2f) { lastInteractionTime = System.currentTimeMillis(); fluxState = FluxState.ACTIVE }
            motionMagnitude = rawMag

            if (fluxState == FluxState.ACTIVE) {
                if (System.currentTimeMillis() - lastInteractionTime > 5000) fluxState = FluxState.MONITOR
                val intensity = (motionMagnitude / 8.0f).coerceIn(0f, 1f)

                if (isAudioMode) { synth.amplitude = 0.2 + (intensity * 0.6); synth.isStandby = false } 
                else { synth.isStandby = true }
                synth.update()

                val now = System.currentTimeMillis()
                if (now - lastNetSend > 50 && intensity > 0f) {
                    lastNetSend = now
                    if (cachedNodes.isNotEmpty()) {
                        val modeByte = if (isAudioMode) 3.toByte() else 1.toByte()
                        val intensityByte = (intensity * 100).toInt().toByte()
                        val payload = byteArrayOf(modeByte, 0, intensityByte)
                        cachedNodes.forEach { node -> Wearable.getMessageClient(context).sendMessage(node.id, "/flux_sync", payload) }
                    }
                }

                if (intensity > 0.05f) {
                    when (activity.reactorProfile) {
                        0 -> { 
                            delay(60); val amp = (intensity * 200).toInt().coerceAtLeast(10)
                            if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(30, amp))
                            else vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        }
                        1 -> { 
                            if (Random.nextFloat() < (intensity * 0.45f)) { vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)); delay(40) } 
                            else delay(30)
                        }
                        2 -> {
                            delay(60); val heavyInt = (intensity * 1.5f).coerceAtMost(1f)
                            val amp = (heavyInt * 255).toInt().coerceAtLeast(20)
                            if (vibrator.hasAmplitudeControl()) vibrator.vibrate(VibrationEffect.createOneShot(80, amp))
                            else vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                        }
                    }
                    logPulse(1, (intensity * 100).toInt())
                } else delay(40)
            } else {
                synth.isStandby = true; synth.update(); delay(100)
            }
        }
    }

    // CLINICAL ENGINE
    LaunchedEffect(isClinicalActive) {
        if (isClinicalActive) {
            val syncDelay = 3000L
            val startTime = System.currentTimeMillis() + syncDelay
            val buf = ByteBuffer.allocate(15)
            buf.put(activity.clinicalProfile.toByte()); buf.putInt(activity.clinicalBpm); buf.put(activity.clinicalIntensity.toByte()); buf.put(if(activity.clinicalSleep) 1.toByte() else 0.toByte()); buf.putLong(startTime)
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes -> nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, "/clinical_start", buf.array()) } }

            for (i in 3 downTo 1) { countdownValue = i; vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)); delay(1000) }
            countdownValue = 0

            val periodMs = 60000.0 / activity.clinicalBpm; var beatCount = 0L
            while(isActive) {
                val cInt = activity.clinicalIntensity
                val cProf = activity.clinicalProfile
                if (cInt > 0) {
                    val amp = (cInt / 100f * 255).toInt().coerceAtLeast(10)
                    if (vibrator.hasAmplitudeControl()) {
                        when(cProf) {
                            0 -> vibrator.vibrate(VibrationEffect.createOneShot(50, amp))
                            1 -> vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                            2 -> vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100), intArrayOf(0, amp), -1))
                        }
                    } else vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    logPulse(2, cInt)
                }
                beatCount++
                val nextBeatTime = startTime + (beatCount * periodMs).toLong()
                val sleepTime = nextBeatTime - System.currentTimeMillis()
                if (sleepTime > 0) delay(sleepTime)
            }
        } else {
            Wearable.getNodeClient(context).connectedNodes.addOnSuccessListener { nodes -> nodes.forEach { Wearable.getMessageClient(context).sendMessage(it.id, "/clinical_stop", byteArrayOf()) } }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FluxBg)
            .onRotaryScrollEvent {
                if (!isLockedDown && !showExitDialog && !activity.isSyncing) { // Lock scrolling during sync
                    scrollAccumulator += it.verticalScrollPixels
                    if (abs(scrollAccumulator) > 60f) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                        lastInteractionTime = System.currentTimeMillis()
                        currentDeck = if (currentDeck == Deck.REACTOR) Deck.CLINICAL else Deck.REACTOR
                        scrollAccumulator = 0f; true
                    } else false
                } else false
            }
            .focusRequester(focusRequester).focusable()
            .pointerInput(fluxState, isLockedDown, currentDeck) {
                detectTapGestures(
                    onDoubleTap = {
                        if (currentDeck == Deck.REACTOR && !isLockedDown && !activity.isSyncing) {
                            val next = (activity.reactorProfile + 1) % 3
                            activity.saveReactorProfile(next)
                            vibrateAck(vibrator)
                            profileNameToast = when(next) { 0 -> "PULSE"; 1 -> "GEIGER"; else -> "THROB" }
                            lastInteractionTime = System.currentTimeMillis(); fluxState = FluxState.ACTIVE
                        }
                    },
                    onTap = { if (!isLockedDown && !activity.isSyncing) { lastInteractionTime = System.currentTimeMillis(); fluxState = FluxState.ACTIVE } }
                )
            }
            .pointerInput(isLockedDown) {
                 if (isLockedDown) {
                     awaitEachGesture {
                         val down = awaitFirstDown(requireUnconsumed = false)
                         val start = System.currentTimeMillis()
                         var holding = true
                         do {
                             val ev = awaitPointerEvent()
                             if (ev.changes.size < 2 && System.currentTimeMillis() - start > 200) holding = false
                             if (holding && ev.changes.size >= 2 && System.currentTimeMillis() - start > 3000) {
                                 isClinicalActive = false; vibrator.vibrate(VibrationEffect.createOneShot(500, 255)); holding = false
                             }
                         } while (ev.changes.any { it.pressed } && holding)
                     }
                 }
            }
    ) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        // --- VISUAL SYNC OVERLAY (Highest Z-Index) ---
        if (activity.isSyncing) {
            val syncProgress = remember { Animatable(0f) }
            
            LaunchedEffect(Unit) {
                // Match phone duration approx 2.5s
                syncProgress.animateTo(1f, animationSpec = tween(2500, easing = LinearEasing))
                delay(200) // Brief hold
                activity.isSyncing = false
                currentDeck = Deck.CLINICAL // Auto-switch to show new config
            }
            
            Box(Modifier.fillMaxSize().zIndex(500f).background(FluxBg), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("[ FIRMWARE UPDATING ]", color = FluxPink, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("RX: CONFIG_PACKET_01", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(15.dp))
                    
                    // Cyber Progress Bar
                    Box(Modifier.width(120.dp).height(8.dp).border(1.dp, FluxCyan).background(FluxDark)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(syncProgress.value).background(FluxCyan))
                    }
                }
            }
        }

        if (curtainAlpha < 1.0f) {
            Text(
                text = if (currentDeck == Deck.REACTOR) "REACTOR" else "CLINICAL",
                color = if (currentDeck == Deck.REACTOR) FluxCyan else FluxPink,
                fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
            )

            if (currentDeck == Deck.REACTOR) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(110.dp)) {
                        CircularProgressIndicator(progress = 1f, indicatorColor = FluxDark, strokeWidth = 6.dp, modifier = Modifier.fillMaxSize())
                        val batCol = if(batteryLevel < 20) FluxPink else if(batteryLevel < 50) Color(0xFFFF9900) else FluxCyan
                        CircularProgressIndicator(progress = batteryLevel / 100f, indicatorColor = batCol, strokeWidth = 6.dp, modifier = Modifier.fillMaxSize())
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("CORE", color = Color.Gray, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                            Text("$batteryLevel%", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(2.dp))
                            Text(timeRemaining, color = FluxCyan, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    FluxButton(
                        text = if (isAudioMode) "AUDIO: ON" else "AUDIO: OFF",
                        onClick = { isAudioMode = !isAudioMode; lastInteractionTime = System.currentTimeMillis() },
                        color = if (isAudioMode) FluxPink else FluxCyan,
                        modifier = Modifier.width(100.dp)
                    )
                }
            } else {
                val pName = when(activity.clinicalProfile) { 0 -> "PULSE"; 1 -> "GEIGER"; else -> "THROB" }
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(15.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal=16.dp), Arrangement.SpaceBetween) {
                         FluxLabel("BPM", "${activity.clinicalBpm}", FluxCyan)
                         FluxLabel("INT", "${activity.clinicalIntensity}%", FluxCyan)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("PROG: ", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text(pName, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    if (activity.clinicalSleep) Text("[SLEEP MODE ACTIVE]", color = FluxPink, fontSize = 8.sp, modifier = Modifier.padding(top = 2.dp))
                    else Spacer(Modifier.height(14.dp))
                    Spacer(Modifier.height(10.dp))
                    FluxButton(text = "INITIALIZE", onClick = { isClinicalActive = true }, color = FluxPink, modifier = Modifier.width(110.dp))
                }
            }
        }
        
        if (profileNameToast.isNotEmpty()) {
            Box(Modifier.fillMaxSize().zIndex(400f), Alignment.Center) {
                Box(Modifier.background(FluxDark.copy(alpha=0.9f), CutCornerShape(10.dp)).border(1.dp, FluxCyan, CutCornerShape(10.dp)).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(profileNameToast, color = FluxCyan, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        
        if (curtainAlpha > 0f) Box(Modifier.fillMaxSize().zIndex(100f).background(FluxBg.copy(alpha=curtainAlpha)))
        if (countdownValue > 0) Box(Modifier.fillMaxSize().zIndex(200f).background(FluxBg), Alignment.Center) { Text("$countdownValue", fontSize = 60.sp, fontWeight = FontWeight.Black, color = FluxPink) }
        
        if (showExitDialog) {
            Box(Modifier.fillMaxSize().zIndex(300f).background(FluxBg.copy(0.95f)), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("TERMINATE?", color = FluxCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(15.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FluxButton("RESUME", { showExitDialog = false }, FluxCyan, Modifier.width(70.dp))
                        FluxButton("HALT", { activity.finish() }, FluxPink, Modifier.width(60.dp))
                    }
                }
            }
        }
    }
}
