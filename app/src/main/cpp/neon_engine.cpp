#include <jni.h>
#include <android/log.h>
#include "NeonSynth.h"
#include "NeonSensors.h"

static std::unique_ptr<NeonSynth> synthEngine = nullptr;
static std::unique_ptr<NeonSensors> sensorEngine = nullptr;

void NeonSensors::updateSynthDirectly(float magnitude) {
    if (synthEngine) {
        synthEngine->setSensorModulation(magnitude);
    }
}

extern "C" {

JNIEXPORT void JNICALL Java_com_snakesan_neonflux_SynthEngine_startNative(JNIEnv *env, jobject thiz) {
    if (synthEngine == nullptr) synthEngine = std::make_unique<NeonSynth>();
    synthEngine->start();
    if (sensorEngine == nullptr) sensorEngine = std::make_unique<NeonSensors>(synthEngine.get());
    sensorEngine->start();
}

JNIEXPORT void JNICALL Java_com_snakesan_neonflux_SynthEngine_stopNative(JNIEnv *env, jobject thiz) {
    if (sensorEngine != nullptr) { sensorEngine->stop(); sensorEngine.reset(); }
    if (synthEngine != nullptr) { synthEngine->stop(); }
}

JNIEXPORT jfloat JNICALL Java_com_snakesan_neonflux_SynthEngine_getSensorMagnitude(JNIEnv *env, jobject thiz) {
    if (sensorEngine != nullptr) return sensorEngine->getMagnitude();
    return 0.0f;
}

JNIEXPORT void JNICALL Java_com_snakesan_neonflux_SynthEngine_updateNative(JNIEnv *env, jobject thiz, jfloat freq, jfloat amp) {
    if (synthEngine != nullptr) synthEngine->updateTargets(freq, amp);
}

JNIEXPORT void JNICALL Java_com_snakesan_neonflux_SynthEngine_setVolumeNative(JNIEnv *env, jobject thiz, jfloat volume) {
    if (synthEngine != nullptr) synthEngine->setMasterVolume(volume);
}

JNIEXPORT void JNICALL Java_com_snakesan_neonflux_SynthEngine_pauseSensorsNative(JNIEnv *env, jobject thiz, jboolean paused) {
    if (sensorEngine != nullptr) sensorEngine->setPaused(paused);
}

// NEW: Telemetry Bridge
// Returns int array: [AudioCallbacksPerSec, SensorEventsPerSec]
JNIEXPORT jintArray JNICALL Java_com_snakesan_neonflux_SynthEngine_getDebugStatsNative(JNIEnv *env, jobject thiz) {
    jintArray result = env->NewIntArray(2);
    jint temp[2];
    
    if (synthEngine) temp[0] = synthEngine->getAndResetCallbackCount();
    else temp[0] = 0;
    
    if (sensorEngine) temp[1] = sensorEngine->getAndResetEventCount();
    else temp[1] = 0;
    
    env->SetIntArrayRegion(result, 0, 2, temp);
    return result;
}

} // extern "C"
