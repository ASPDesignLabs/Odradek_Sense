#ifndef NEONFLUX_NEONSENSORS_H
#define NEONFLUX_NEONSENSORS_H

#include <android/sensor.h>
#include <android/looper.h>
#include <android/log.h>
#include <cmath>
#include <thread>
#include <atomic>
#include "NeonSynth.h" // Full include needed to call methods

class NeonSensors {
public:
    NeonSensors(NeonSynth* synth) : mSynth(synth) {}

    ~NeonSensors() { stop(); }

    void start() {
        if (mIsRunning) return;
        mSensorManager = ASensorManager_getInstanceForPackage("com.snakesan.neonflux");
        if (!mSensorManager) return;

        mAccelerometer = ASensorManager_getDefaultSensor(mSensorManager, ASENSOR_TYPE_LINEAR_ACCELERATION);
        if (!mAccelerometer) return;

        mIsRunning = true;
        mSensorThread = std::thread(&NeonSensors::pollLoop, this);
        setPaused(false);
    }

    void stop() {
        mIsRunning = false;
        if (mLooper) ALooper_wake(mLooper);
        if (mSensorThread.joinable()) mSensorThread.join();
    }
    
    void setPaused(bool paused) {
        if (!mEventQueue || !mAccelerometer) return;
        if (paused) {
            ASensorEventQueue_disableSensor(mEventQueue, mAccelerometer);
        } else {
            ASensorEventQueue_enableSensor(mEventQueue, mAccelerometer);
            ASensorEventQueue_setEventRate(mEventQueue, mAccelerometer, 40000); 
        }
    }

    float getMagnitude() { return mCurrentMagnitude.load(); }
    int getAndResetEventCount() { return mEventCounter.exchange(0); }

private:
    NeonSynth* mSynth;
    ASensorManager* mSensorManager = nullptr;
    const ASensor* mAccelerometer = nullptr;
    ASensorEventQueue* mEventQueue = nullptr;
    ALooper* mLooper = nullptr;
    std::atomic<bool> mIsRunning{false};
    std::thread mSensorThread;
    std::atomic<float> mCurrentMagnitude{0.0f};
    std::atomic<int> mEventCounter{0};
    
    // NEW: Stillness Tracker
    int mStillnessFrameCounter = 0;
    const int kStillnessThreshold = 50; // 50 frames @ 40ms = 2.0 seconds

    void pollLoop() {
        mLooper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
        mEventQueue = ASensorManager_createEventQueue(mSensorManager, mLooper, 1, nullptr, nullptr);
        
        if (mEventQueue && mAccelerometer) {
            ASensorEventQueue_enableSensor(mEventQueue, mAccelerometer);
            ASensorEventQueue_setEventRate(mEventQueue, mAccelerometer, 40000);
        }

        while (mIsRunning) {
            int ident;
            int events;
            void* data;
            while ((ident = ALooper_pollOnce(200, nullptr, &events, &data)) >= 0) {
                if (ident == 1) {
                    ASensorEvent event;
                    while (ASensorEventQueue_getEvents(mEventQueue, &event, 1) > 0) {
                        mEventCounter.fetch_add(1);
                        
                        float x = event.acceleration.x;
                        float y = event.acceleration.y;
                        float z = event.acceleration.z;
                        float mag = std::sqrt(x*x + y*y + z*z);

                        float current = mCurrentMagnitude.load();
                        float smoothed = (current * 0.8f) + (mag * 0.2f);
                        mCurrentMagnitude.store(smoothed);
                        
                        // --- MICRO-HIBERNATION LOGIC ---
                        if (smoothed < 0.1f) {
                            // If we are still
                            mStillnessFrameCounter++;
                            if (mStillnessFrameCounter == kStillnessThreshold) {
                                // We just crossed the 2-second mark. Kill the Amp.
                                if (mSynth) mSynth->suspend();
                            }
                        } else {
                            // MOVEMENT DETECTED
                            // If we were sleeping, wake up instantly
                            if (mStillnessFrameCounter >= kStillnessThreshold) {
                                if (mSynth) mSynth->resume();
                            }
                            mStillnessFrameCounter = 0;
                        }

                        // Feed frequency logic (even if suspended, keeps state ready)
                        updateSynthDirectly(smoothed);
                    }
                }
                if (!mIsRunning) break;
            }
        }
        
        if (mEventQueue) {
            ASensorEventQueue_disableSensor(mEventQueue, mAccelerometer);
            ASensorManager_destroyEventQueue(mSensorManager, mEventQueue);
            mEventQueue = nullptr;
        }
        mLooper = nullptr;
    }

    void updateSynthDirectly(float magnitude);
};

#endif //NEONFLUX_NEONSENSORS_H
