Odradek Sense


Odradek Sense is a multimodal sensory grounding tool designed to bridge the gap between digital input and physical sensation. Inspired by the "Bridges" concept from Death Stranding, this experience creates a unified haptic feedback loop between a Pixel Watch and an Android phone.

The app serves as a stimming aid and grounding device, translating touch, motion, and audio into synthesized haptic textures. 

Features

⌚ The Watch Experience (Wear OS)


The watch acts as the primary sensor and controller.


- Live Visuals: A fluid, decaying light trail system that reacts to touch.

- Haptic Synthesis: Real-time vibration generation based on input intensity.

- Procedural Audio: An integrated synth engine that generates audio frequencies synced to the vibration texture.

- Rotary Control: Use the digital crown to seamlessly switch modes with dampened, haptic-feedback scrolling.

📱 The Phone Bridge (Mobile)


The phone acts as a secondary grounding anchor.


- Zero-Latency Sync: Mirrors the haptic intensity of the watch in real-time via a direct Bluetooth message layer.

- Background Focus: Utilizes a Foreground Service to maintain haptic connection even when the phone is locked and the screen is off.

- Visual Mirror: Displays a color-coded intensity visualization that matches the watch state.

Interaction Modes


Select a mode using the Crown or the on-screen menu:


1. TOUCH (Silent): Drag on the screen to generate haptics. Vibration strength is determined by distance from the screen center (Deadzone = Max Intensity).

2. MOTION (Silent): Uses the linear accelerometer to translate arm movement into vibration. Holding still creates silence; swinging creates feedback.

3. TOUCH + AUDIO: Adds a rising/falling synth drone to the touch interaction.

4. MOTION + AUDIO: A "Theremin-like" experience where movement controls both sound and physical sensation.

Gestures & Controls

- Start Drawing: Hold finger on screen for 0.5 seconds to engage the sensor (prevents accidental input).

- Change Texture: Double-tap anywhere to cycle haptic profiles:
	- Dynamo: Continuous, amplitude-based hum.

	- Geiger: Discrete clicks that increase in frequency.

	- Throb: Slow, heavy heartbeat pulse (only active in the center).


- Menu: Tap the bottom mode chip to open the selection list.

Installation

Prerequisites

- Watch: Google Pixel Watch 4 (or Wear OS 4+ device).

- Phone: Android 12+ (Pixel 7 or newer recommended).

- Permissions: Both devices require Bluetooth Connect and Notification permissions to function properly.

Build Instructions

1. Open the project in Android Studio.

2. Sync Gradle files.

3. Watch: Select the app configuration and deploy to the watch.

4. Phone: Select the neonfluxmobile configuration and deploy to the phone.

5. Launch: Open both apps. Accept the "Nearby Devices" permission on the phone to enable background sync.

Tech Stack

- Language: Kotlin

- UI: Jetpack Compose (Wear & Mobile)

- Sensors: Android SensorManager (Linear Acceleration) & VibratorManager (Haptic Primitives).

- Audio: AudioTrack API (Raw PCM Synthesis).

- Connectivity: Google Play Services Wearable Data Layer (MessageClient).


---
"Everything is connected."
