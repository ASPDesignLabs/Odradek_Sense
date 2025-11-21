package com.snakesan.neonflux

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.*
import android.os.*
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.*
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.*
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { MaterialTheme { OdradekSense() } }
    }
}

// --- MODELS & ENGINES ---
data class LightPoint(val x: Float, val y: Float, var life: Float = 1.0f, val color: Color)

class SynthEngine {
    private val sampleRate = 44100
    private val buffSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    private var audioTrack: AudioTrack? = null
    private var isRunning = false
    var frequency = 440.0; var amplitude = 0.0; private var phase = 0.0

    fun start() {
        if (isRunning) return
        audioTrack = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(buffSize).setTransferMode(AudioTrack.MODE_STREAM).build()
        audioTrack?.play(); isRunning = true
        Thread {
            val buffer = ShortArray(buffSize / 2)
            while (isRunning) {
                for (i in buffer.indices) {
                    val angle = 2.0 * Math.PI * frequency * (phase / sampleRate)
                    val sample = (sin(angle) * (amplitude * Short.MAX_VALUE)).toInt().toShort()
                    buffer[i] = sample; phase++
                }
                if (phase > sampleRate) phase -= sampleRate
                try { audioTrack?.write(buffer, 0, buffer.size) } catch (e: Exception) {}
            }
        }.start()
    }
    fun stop() { isRunning = false; try { audioTrack?.stop(); audioTrack?.release() } catch (e: Exception) {} }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OdradekSense() {
    val context = LocalContext.current
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    // --- STATE ---
    var timeText by remember { mutableStateOf("") }
    val points = remember { mutableStateListOf<LightPoint>() }

    // 0=Touch, 1=Motion, 2=Touch+Snd, 3=Motion+Snd
    var modeIndex by remember { mutableIntStateOf(0) }
    var isMenuOpen by remember { mutableStateOf(false) }

    val isMotionMode = (modeIndex == 1 || modeIndex == 3)
    val isSoundEnabled = (modeIndex == 2 || modeIndex == 3)

    var motionMagnitude by remember { mutableFloatStateOf(0f) }
    var touchIntensity by remember { mutableFloatStateOf(0f) }
    var isTouching by remember { mutableStateOf(false) }
    var hapticProfile by remember { mutableIntStateOf(0) }
    var colorIndex by remember { mutableIntStateOf(0) }
    val neonColors = listOf(Color(0xFF00FFCC), Color(0xFFD400FF), Color(0xFFCCFF00))
    val hapticNames = listOf("Dynamo", "Geiger", "Throb")

    // Audio & Haptics
    val synth = remember { SynthEngine() }
    val vibrator = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator else context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator }

    // DAMPENING VARIABLES
    var scrollAccumulator by remember { mutableFloatStateOf(0f) }
    val scrollThreshold = 60f // Pixels of rotation needed to switch mode

    DisposableEffect(Unit) { synth.start(); onDispose { synth.stop() } }

    // --- SENSORS & LOOPS ---
    DisposableEffect(isMotionMode) {
        if (isMotionMode) {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) { event?.let { val mag = sqrt(it.values[0].pow(2) + it.values[1].pow(2) + it.values[2].pow(2)); motionMagnitude = (motionMagnitude * 0.8f) + (mag * 0.2f) } }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME); onDispose { sensorManager.unregisterListener(listener) }
        } else { onDispose { } }
    }

    LaunchedEffect(Unit) { while (true) { timeText = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")); delay(1000) } }
    LaunchedEffect(Unit) { while (true) { withFrameNanos { val i = points.iterator(); while(i.hasNext()) { val p=i.next(); p.life-=0.02f; if(p.life<=0f) i.remove() } } } }

    // --- FEEDBACK LOOP ---
    LaunchedEffect(modeIndex, hapticProfile, isTouching) {
        var lastNetworkSend = 0L
        while (isActive) {
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
                when (hapticProfile) {
                    0 -> { if (vibrator.hasAmplitudeControl()) { val amp = (intensity * 255).toInt().coerceAtLeast(10); vibrator.vibrate(VibrationEffect.createOneShot(30, amp)); delay(20) } else { vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)); delay(50) } }
                    1 -> { val delayTime = (10 + ((1.0f - intensity).pow(2)) * 400).toLong(); vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)); if (isSoundEnabled) { synth.amplitude = 0.8; delay(20); synth.amplitude = 0.0; delay(delayTime - 20) } else { delay(delayTime) } }
                    2 -> { if (intensity > 0.4f) { val beatDelay = (600 - (intensity * 400)).toLong(); vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)); if(isSoundEnabled) { synth.amplitude = 0.8; delay(100); synth.amplitude = 0.0; delay(beatDelay - 100) } else { delay(beatDelay) } } else { delay(100) } }
                }
            } else { synth.amplitude = 0.0; delay(50) }
        }
    }

    // --- UI LAYER ---
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(Color.Black)
    ) {
        // Capture Screen Width for touch logic
        val screenWidthPx = with(density) { maxWidth.toPx() }
        val screenCenter = screenWidthPx / 2f

        // Outer Box handles Rotary events
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    if (!isMenuOpen) {
                        scrollAccumulator += it.verticalScrollPixels
                        if (abs(scrollAccumulator) > scrollThreshold) {
                            val direction = if (scrollAccumulator > 0) 1 else -1
                            // Crisp Haptic Click on change
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))

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

            // 1. VISUALIZER (Inside Box to access pointerInput)
// 1. VISUALIZER
            if (!isMenuOpen) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // A: THE DRAWING LAYER (Background)
                    // We use 'detectDragGesturesAfterLongPress' to allow clicks to pass through
                    // to buttons if the user doesn't hold down.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                if (!isMotionMode && !isMenuOpen) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { offset ->
                                            // Haptic bump to let user know "It's active now"
                                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                                            isTouching = true
                                            points.add(LightPoint(offset.x, offset.y, 1.0f, neonColors[colorIndex]))
                                        },
                                        onDrag = { change, _ ->
                                            isTouching = true
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
                                // Separate tap detector for the Double Tap shortcut
                                // We keep this separate so double-tapping still works quickly
                                detectTapGestures(onDoubleTap = {
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

                    // B: THE UI LAYER (Foreground)
                    // Placed AFTER the drawing layer in the code so it renders ON TOP
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Clock (Centered)
                        val clockColor = when(modeIndex) { 0 -> Color.White; 1 -> Color(0xFFFF5555); 2 -> Color(0xFF00FFFF); 3 -> Color(0xFFFF00FF); else -> Color.White }
                        val scale = if (isMotionMode) 1.0f + (motionMagnitude/15f).coerceIn(0f, 0.5f) else 1.0f

                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(text = timeText, style = TextStyle(color = clockColor, fontSize = (54 * scale).sp, fontWeight = FontWeight.Thin, shadow = Shadow(color = clockColor, blurRadius = 30f)))
                        }

                        // Menu Button (Bottom)
                        // Now that the touch layer requires a long press, this button
                        // will receive standard clicks immediately.
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

            // 2. MENU OVERLAY
            if (isMenuOpen) {
                val listState = rememberScalingLazyListState()
                LaunchedEffect(Unit) { focusRequester.requestFocus() }

                ScalingLazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha=0.95f))
                        .onRotaryScrollEvent {
                            coroutineScope.launch { listState.scrollBy(it.verticalScrollPixels) }
                            true
                        },
                    state = listState,
                    scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.5f, edgeAlpha = 0.5f)                ) {
                    item { Text("ODRADEK MODE", color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom=5.dp)) }
                    item { ModeChip("Touch (Silent)", 0, modeIndex) { modeIndex = 0; isMenuOpen = false } }
                    item { ModeChip("Motion (Silent)", 1, modeIndex) { modeIndex = 1; isMenuOpen = false } }
                    item { ModeChip("Touch + Audio", 2, modeIndex) { modeIndex = 2; isMenuOpen = false } }
                    item { ModeChip("Motion + Audio", 3, modeIndex) { modeIndex = 3; isMenuOpen = false } }
                    item {
                        Button(
                            onClick = { isMenuOpen = false },
                            colors = ButtonDefaults.buttonColors(backgroundColor = Color.DarkGray),
                            modifier = Modifier.padding(top=10.dp).size(40.dp)
                        ) { Text("X") }
                    }
                }
            }
        }
    }
}

@Composable
fun ModeChip(label: String, index: Int, currentIndex: Int, onClick: () -> Unit) {
    val isSelected = index == currentIndex
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        colors = if (isSelected) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
    )
}