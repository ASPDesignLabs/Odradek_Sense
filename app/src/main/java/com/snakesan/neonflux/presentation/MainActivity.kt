package com.snakesan.neonflux

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.media.*
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { OdradekSense(this) } }
    }
}

data class LightPoint(val x: Float, val y: Float, var life: Float = 1.0f, val color: Color)

enum class FluxState { MONITOR, ACTIVE }

// --- OPTIMIZATION: THE BITCRUSHER SYNTH ---
class SynthEngine {
    // CHANGE 1: Drop Sample Rate to 11025Hz (Low-Fi)
    // This reduces CPU load by 75% compared to 44100Hz
    private val sampleRate = 11025 
    
    private val buffSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    var isStandby = true 
    
    private val tableSize = 4096
    private val sineTable = FloatArray(tableSize)
    var frequency = 440.0
    var amplitude = 0.0
    private var currentPhase = 0.0

    init {
        // Pre-calculate sine table
        for (i in 0 until tableSize) {
            sineTable[i] = sin(2.0 * Math.PI * i / tableSize).toFloat()
        }
    }

    fun start() {
        if (isRunning) return
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buffSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()
        isRunning = true
        
        Thread {
            val buffer = ShortArray(buffSize) // Use full buffer size
            while (isRunning) {
                if (isStandby || amplitude <= 0.01) {
                    buffer.fill(0)
                    // Sleep longer in standby (100ms)
                    try { Thread.sleep(100) } catch (e: Exception) {}
                    try { audioTrack?.write(buffer, 0, buffer.size) } catch (e: Exception) {}
                } else {
                    val phaseIncrement = frequency / sampleRate
                    
                    // Unroll loop slightly for speed (optional, but good practice)
                    for (i in buffer.indices) {
                        val tableIndex = (currentPhase * tableSize).toInt() % tableSize
                        val rawSample = sineTable[tableIndex]
                        buffer[i] = (rawSample * (amplitude * Short.MAX_VALUE)).toInt().toShort()
                        currentPhase += phaseIncrement
                        if (currentPhase >= 1.0) currentPhase -= 1.0
                    }
                    // Blocking write - this naturally paces the thread to the sample rate
                    try { audioTrack?.write(buffer, 0, buffer.size) } catch (e: Exception) {}
                }
            }
        }.start()
    }
    fun stop() { isRunning = false; try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {} }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OdradekSense(activity: ComponentActivity) {
    val context = LocalContext.current
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    var timeText by remember { mutableStateOf("") }
    val points = remember { mutableStateListOf<LightPoint>() }
    var modeIndex by remember { mutableIntStateOf(0) }
    var isMenuOpen by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    val isMotionMode = (modeIndex == 1 || modeIndex == 3)
    val isSoundEnabled = (modeIndex == 2 || modeIndex == 3)

    var motionMagnitude by remember { mutableFloatStateOf(0f) }
    var touchIntensity by remember { mutableFloatStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var hapticProfile by remember { mutableIntStateOf(0) }
    var colorIndex by remember { mutableIntStateOf(0) }
    val neonColors = listOf(Color(0xFF00FFCC), Color(0xFFD400FF), Color(0xFFCCFF00))
    val hapticNames = listOf("Dynamo", "Geiger", "Throb")

    var fluxState by remember { mutableStateOf(FluxState.MONITOR) }
    var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val shouldDarken = isMotionMode && fluxState == FluxState.ACTIVE && !isTouching && !isMenuOpen && !showExitDialog
    val curtainAlpha by animateFloatAsState(
        targetValue = if (shouldDarken) 1f else 0f,
        animationSpec = tween(durationMillis = 2000)
    )

    val synth = remember { SynthEngine() }
    val vibrator = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    val scrollThreshold = 60f 

    BackHandler(enabled = !showExitDialog) { showExitDialog = true }

    DisposableEffect(Unit) { synth.start(); onDispose { synth.stop() } }

    // --- TELEMETRY ---
    LaunchedEffect(Unit) {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        Log.d("NEON_PWR", "Time,State,Mode,ScreenAlpha,Motion,MicroAmps")
        if (batteryManager != null) {
            while(isActive) {
                val microAmps = abs(batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW))
                val stateInt = if (fluxState == FluxState.ACTIVE) 1 else 0
                val screenVisibility = "%.2f".format(1f - curtainAlpha)
                val motionFmt = "%.2f".format(motionMagnitude)
                val time = System.currentTimeMillis()
                Log.d("NEON_PWR", "$time,$stateInt,$modeIndex,$screenVisibility,$motionFmt,$microAmps")
                delay(1000)
            }
        }
    }

    // --- SENSORS ---
    val triggerListener = remember {
        object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                lastInteractionTime = System.currentTimeMillis()
                fluxState = FluxState.ACTIVE
            }
        }
    }

    DisposableEffect(isMotionMode, fluxState) {
        if (isMotionMode) {
            val linearAccel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            val sigMotion = sensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
            val accelListener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) { 
                    event?.let { 
                        val mag = sqrt(it.values[0].pow(2) + it.values[1].pow(2) + it.values[2].pow(2))
                        if (mag > 1.2f) { lastInteractionTime = System.currentTimeMillis() }
                        motionMagnitude = (motionMagnitude * 0.8f) + (mag * 0.2f) 
                    } 
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            if (fluxState == FluxState.ACTIVE) {
                if (linearAccel != null) sensorManager.registerListener(accelListener, linearAccel, SensorManager.SENSOR_DELAY_GAME)
                if (sigMotion != null) sensorManager.cancelTriggerSensor(triggerListener, sigMotion)
            } else {
                if (sigMotion != null) {
                    sensorManager.requestTriggerSensor(triggerListener, sigMotion)
                } else {
                    if (linearAccel != null) sensorManager.registerListener(accelListener, linearAccel, SensorManager.SENSOR_DELAY_NORMAL)
                }
            }
            onDispose { 
                sensorManager.unregisterListener(accelListener)
                if (sigMotion != null) sensorManager.cancelTriggerSensor(triggerListener, sigMotion)
            }
        } else { onDispose { } }
    }

    // --- STATE LOOP ---
    LaunchedEffect(Unit) {
        while(isActive) {
            val now = System.currentTimeMillis()
            val timeSinceInteraction = now - lastInteractionTime
            if (fluxState == FluxState.ACTIVE) {
                if (timeSinceInteraction > 5000) {
                    fluxState = FluxState.MONITOR
                    motionMagnitude = 0f 
                }
            } else {
                if (timeSinceInteraction < 200) { fluxState = FluxState.ACTIVE }
            }
            synth.isStandby = (fluxState == FluxState.MONITOR)
            delay(500)
        }
    }

    LaunchedEffect(Unit) { while (true) { timeText = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")); delay(1000) } }
    
    // --- VISUAL PHYSICS LOOP (OPTIMIZED) ---
    LaunchedEffect(Unit) { 
        while (true) { 
            // CHANGE 2: If the curtain is black, pause the loop entirely.
            if (curtainAlpha >= 1.0f) {
                delay(500) // Sleep CPU while screen is dark
            } else {
                withFrameNanos { 
                    if (points.isNotEmpty()) {
                        val i = points.iterator(); 
                        while(i.hasNext()) { val p=i.next(); p.life-=0.02f; if(p.life<=0f) i.remove() } 
                    }
                }
            }
        } 
    }

    // --- FEEDBACK LOOP ---
    LaunchedEffect(modeIndex, hapticProfile, isTouching, fluxState) {
        var lastNetworkSend = 0L
        while (isActive) {
            if (fluxState == FluxState.MONITOR && !isTouching) {
                delay(200)
                continue
            }
            
            var intensity = 0f
            if (isMotionMode) { intensity = (motionMagnitude / 8.0f).coerceIn(0f, 1f); if (intensity < 0.05f) intensity = 0f }
            else if (isTouching) { intensity = touchIntensity }

            val currentTime = System.currentTimeMillis()
            if (currentTime - lastNetworkSend > 50 && intensity > 0f) {
                lastNetworkSend = currentTime
                coroutineScope.launch(Dispatchers.IO) {
                    try {
                        val payload = byteArrayOf(modeIndex.toByte(), hapticProfile.toByte(), (intensity * 100).toInt().toByte())
                        val nodeClient = Wearable.getNodeClient(context); val msgClient = Wearable.getMessageClient(context)
                        val nodes = com.google.android.gms.tasks.Tasks.await(nodeClient.connectedNodes)
                        nodes.forEach { node -> msgClient.sendMessage(node.id, "/flux_sync", payload) }
                    } catch (e: Exception) {}
                }
            }

            if (isSoundEnabled && intensity > 0f) {
                synth.amplitude = 0.5
                when (hapticProfile) { 0 -> synth.frequency = 200.0+(intensity*600.0); 1 -> synth.frequency = 1200.0; 2 -> synth.frequency = 60.0+(intensity*40.0) }
            } else { synth.amplitude = 0.0 }

            if (intensity > 0f) {
                // CHANGE 3: Cap Haptic Intensity to 200/255 (approx 80%) to save battery
                // Haptic motors are power hungry at 100%
                when (hapticProfile) {
                    0 -> { 
                        if (vibrator.hasAmplitudeControl()) { 
                            // Cap max amplitude to 200
                            val amp = (intensity * 200).toInt().coerceAtLeast(10)
                            vibrator.vibrate(VibrationEffect.createOneShot(30, amp))
                            delay(40) 
                        } else { 
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                            delay(60) 
                        } 
                    }
                    1 -> { 
                        val delayTime = (40 + ((1.0f - intensity).pow(2)) * 400).toLong()
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                        if (isSoundEnabled) { 
                            synth.amplitude = 0.8; delay(20); synth.amplitude = 0.0
                            delay(delayTime - 20) 
                        } else { delay(delayTime) } 
                    }
                    2 -> { 
                        if (intensity > 0.4f) { 
                            val beatDelay = (600 - (intensity * 400)).toLong()
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                            if(isSoundEnabled) { 
                                synth.amplitude = 0.8; delay(100); synth.amplitude = 0.0
                                delay(beatDelay - 100) 
                            } else { delay(beatDelay) } 
                        } else { delay(100) } 
                    }
                }
            } else { synth.amplitude = 0.0; delay(50) }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenCenter = screenWidthPx / 2f

        Box(
            modifier = Modifier.fillMaxSize()
                .onRotaryScrollEvent {
                    if (!isMenuOpen && !showExitDialog) {
                        scrollAccumulator += it.verticalScrollPixels
                        if (abs(scrollAccumulator) > scrollThreshold) {
                            val direction = if (scrollAccumulator > 0) 1 else -1
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                            lastInteractionTime = System.currentTimeMillis() 
                            fluxState = FluxState.ACTIVE
                            var newIndex = modeIndex + direction
                            if (newIndex > 3) newIndex = 0; if (newIndex < 0) newIndex = 3
                            modeIndex = newIndex
                            scrollAccumulator = 0f
                            true
                        } else { false }
                    } else { false }
                }
                .focusRequester(focusRequester)
                .focusable()
        ) {
            LaunchedEffect(Unit) { focusRequester.requestFocus() }

            if (!isMenuOpen && curtainAlpha < 1.0f) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(Unit) {
                                if (!isMotionMode && !isMenuOpen && !showExitDialog) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                                            isTouching = true
                                            lastInteractionTime = System.currentTimeMillis()
                                            fluxState = FluxState.ACTIVE
                                            points.add(LightPoint(offset.x, offset.y, 1.0f, neonColors[colorIndex]))
                                        },
                                        onDrag = { change, _ ->
                                            isTouching = true
                                            lastInteractionTime = System.currentTimeMillis()
                                            fluxState = FluxState.ACTIVE
                                            points.add(LightPoint(change.position.x, change.position.y, 1.0f, neonColors[colorIndex]))
                                            val d = sqrt((change.position.x - screenCenter).pow(2) + (change.position.y - screenCenter).pow(2))
                                            touchIntensity = 1.0f - (d / screenCenter).coerceIn(0f, 1f)
                                        },
                                        onDragEnd = { isTouching = false; colorIndex = (colorIndex + 1) % neonColors.size },
                                        onDragCancel = { isTouching = false }
                                    )
                                }
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onDoubleTap = {
                                    lastInteractionTime = System.currentTimeMillis()
                                    fluxState = FluxState.ACTIVE
                                    hapticProfile = (hapticProfile + 1) % 3
                                    Toast.makeText(context, "Texture: ${hapticNames[hapticProfile]}", Toast.LENGTH_SHORT).show()
                                })
                            }
                    ) {
                        if (!isMotionMode) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                points.forEach { pt -> drawCircle(color = pt.color.copy(alpha = max(0f, pt.life)), radius = 15.dp.toPx() * pt.life, center = Offset(pt.x, pt.y)) }
                                drawCircle(Color.DarkGray, radius=10.dp.toPx(), style=androidx.compose.ui.graphics.drawscope.Stroke(2f))
                            }
                        }
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        val clockColor = when(modeIndex) { 0 -> Color.White; 1 -> Color(0xFFFF5555); 2 -> Color(0xFF00FFFF); 3 -> Color(0xFFFF00FF); else -> Color.White }
                        val dimming = if (fluxState == FluxState.MONITOR) 0.5f else 1.0f
                        val scale = if (isMotionMode) 1.0f + (motionMagnitude/15f).coerceIn(0f, 0.5f) else 1.0f

                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(text = timeText, style = TextStyle(color = clockColor.copy(alpha=dimming), fontSize = (54 * scale).sp, fontWeight = FontWeight.Thin, shadow = Shadow(color = clockColor.copy(alpha=dimming), blurRadius = 30f)))
                        }

                        Box(modifier = Modifier.padding(bottom = 10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            CompactChip(
                                onClick = { isMenuOpen = true },
                                label = { Text(when(modeIndex) { 0->"TOUCH"; 1->"MOTION"; 2->"TOUCH + AUDIO"; 3->"MOTION + AUDIO"; else->"" }, fontSize = 10.sp) },
                                colors = ChipDefaults.secondaryChipColors()
                            )
                        }
                    }
                }
            }

            if (curtainAlpha > 0f) {
                Box(modifier = Modifier.fillMaxSize().zIndex(100f).background(Color.Black.copy(alpha = curtainAlpha))
                        .pointerInput(Unit) { detectTapGestures(onTap = { lastInteractionTime = System.currentTimeMillis(); fluxState = FluxState.ACTIVE }) }
                )
            }

            if (isMenuOpen) {
                val listState = rememberScalingLazyListState()
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize().zIndex(200f).background(Color.Black.copy(alpha=0.95f)).onRotaryScrollEvent { coroutineScope.launch { listState.scrollBy(it.verticalScrollPixels) }; true },
                    state = listState,
                    scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.5f, edgeAlpha = 0.5f)                
                ) {
                    item { Text("ODRADEK MODE", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom=5.dp)) }
                    item { ModeChip("Touch (Silent)", 0, modeIndex) { modeIndex = 0; isMenuOpen = false } }
                    item { ModeChip("Motion (Silent)", 1, modeIndex) { modeIndex = 1; isMenuOpen = false } }
                    item { ModeChip("Touch + Audio", 2, modeIndex) { modeIndex = 2; isMenuOpen = false } }
                    item { ModeChip("Motion + Audio", 3, modeIndex) { modeIndex = 3; isMenuOpen = false } }
                    item { Button(onClick = { isMenuOpen = false }, colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray), modifier = Modifier.padding(top=10.dp).size(40.dp)) { Text("X") } }
                }
            }
            
            if (showExitDialog) {
                Box(modifier = Modifier.fillMaxSize().zIndex(300f).background(Color.Black.copy(alpha=0.9f)).pointerInput(Unit) { detectTapGestures {} }, contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("POWER DOWN?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(bottom=15.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            CompactButton(onClick = { showExitDialog = false }, colors = ButtonDefaults.secondaryButtonColors()) { Text("NO") }
                            CompactButton(onClick = { activity.finish() }, colors = ButtonDefaults.primaryButtonColors(backgroundColor = Color(0xFFFF5555))) { Text("OFF") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeChip(label: String, index: Int, currentIndex: Int, onClick: () -> Unit) {
    val isSelected = index == currentIndex
    Chip(onClick = onClick, label = { Text(label, maxLines = 1) }, colors = if (isSelected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(), modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp))
}
