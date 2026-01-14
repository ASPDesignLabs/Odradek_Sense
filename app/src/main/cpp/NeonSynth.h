#ifndef NEONFLUX_NEONSYNTH_H
#define NEONFLUX_NEONSYNTH_H

#include <oboe/Oboe.h>
#include <cmath>
#include <vector>
#include <atomic>

class NeonSynth : public oboe::AudioStreamCallback {
public:
    NeonSynth() {
        mWavetable.resize(kTableSize);
        for (int i = 0; i < kTableSize; ++i) {
            mWavetable[i] = std::sin(2.0 * M_PI * i / kTableSize);
        }
    }

    virtual ~NeonSynth() { stop(); }

    void start() {
        oboe::AudioStreamBuilder builder;
        builder.setDirection(oboe::Direction::Output);
        builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);
        builder.setSharingMode(oboe::SharingMode::Shared);
        builder.setFormat(oboe::AudioFormat::Float);
        builder.setChannelCount(oboe::ChannelCount::Mono);
        builder.setSampleRate(oboe::Unspecified);
        builder.setCallback(this);
        
        oboe::Result result = builder.openStream(mStream);
        if (result == oboe::Result::OK) mStream->requestStart();
    }

    void stop() {
        if (mStream) { mStream->close(); mStream.reset(); }
    }

    // NEW: Called by Sensor Thread to save power when still
    void suspend() {
        if (mStream && mStream->getState() == oboe::StreamState::Started) {
            // This allows the hardware amp to turn off
            mStream->requestPause();
        }
    }

    // NEW: Called by Sensor Thread to wake up instantly
    void resume() {
        // Only resume if we are paused/stopping. 
        // We check specific states to avoid spamming the driver.
        if (mStream) {
            oboe::StreamState state = mStream->getState();
            if (state == oboe::StreamState::Paused || 
                state == oboe::StreamState::Pausing || 
                state == oboe::StreamState::Stopped) {
                mStream->requestStart();
            }
        }
    }

    void setParameters(float frequency, float amplitude) {
        mTargetFrequency.store(frequency);
        mTargetAmplitude.store(amplitude);
    }
    
    void setSensorModulation(float intensity) {
        float targetFreq = 100.0f + (intensity * 200.0f);
        mTargetFrequency.store(targetFreq);
    }

    void setMasterVolume(float volume) {
        float safeGain = volume * 0.4f;
        mMasterGain.store(safeGain);
    }
    
    int getAndResetCallbackCount() { return mCallbackCounter.exchange(0); }

    void updateTargets(float freq, float amp) {
        mTargetFrequency.store(freq);
        mTargetAmplitude.store(amp);
    }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *oboeStream, void *audioData, int32_t numFrames) override {
        mCallbackCounter.fetch_add(1);

        float *floatData = (float *) audioData;
        float targetAmp = mTargetAmplitude.load();
        float targetFreq = mTargetFrequency.load();
        float currentMasterGain = mMasterGain.load();

        if (targetAmp <= 0.001f && mCurrentAmplitude <= 0.001f) {
            if (!mIsSilent) {
                std::fill_n(floatData, numFrames, 0.0f);
                mCurrentAmplitude = 0.0f;
                mIsSilent = true; 
            } else {
                std::fill_n(floatData, numFrames, 0.0f);
            }
            return oboe::DataCallbackResult::Continue;
        }
        
        mIsSilent = false;

        float ampStep = (targetAmp - mCurrentAmplitude) / numFrames;
        float freqStep = (targetFreq - mCurrentFrequency) / numFrames;
        double sampleRate = oboeStream->getSampleRate();
        double phaseScalar = kTableSize / sampleRate;

        for (int i = 0; i < numFrames; ++i) {
            mCurrentAmplitude += ampStep;
            mCurrentFrequency += freqStep;

            int index = static_cast<int>(mPhase) & (kTableSize - 1);
            float rawSample = mWavetable[index];
            float finalSample = rawSample * mCurrentAmplitude * currentMasterGain;
            floatData[i] = finalSample;

            mPhase += mCurrentFrequency * phaseScalar;
            if (mPhase >= kTableSize) mPhase -= kTableSize;
        }
        
        mCurrentAmplitude = targetAmp;
        mCurrentFrequency = targetFreq;

        return oboe::DataCallbackResult::Continue;
    }

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    std::vector<float> mWavetable;
    const int kTableSize = 4096;
    std::atomic<float> mMasterGain{0.3f};
    double mPhase = 0.0;
    float mCurrentAmplitude = 0.0f;
    float mCurrentFrequency = 440.0f;
    std::atomic<float> mTargetFrequency{440.0f};
    std::atomic<float> mTargetAmplitude{0.0f};
    std::atomic<int> mCallbackCounter{0};
    bool mIsSilent = false;
};

#endif //NEONFLUX_NEONSYNTH_H
