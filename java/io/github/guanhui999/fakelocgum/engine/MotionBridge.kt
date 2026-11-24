package io.github.guanhui999.fakelocgum.engine

import android.hardware.Sensor
import android.os.SystemClock
import android.util.Log
import io.github.guanhui999.fakelocgum.core.SpoofConfig

/** JNI bridge shared by the Java callback path and the Frida Gum NDK hook. */
internal object MotionBridge {

    @Volatile
    private var loaded = false

    private var lastAttemptMs = -10_000L

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        val now = SystemClock.elapsedRealtime()
        if (now - lastAttemptMs < 10_000L) return false
        lastAttemptMs = now
        return runCatching {
            System.loadLibrary("fakeloc_motion")
            nativeInit()
            loaded = true
        }.onFailure { Log.e("FakeLocGum/Motion", "native load failed; Java location path remains active", it) }.isSuccess
    }

    fun configure(config: SpoofConfig, enabled: Boolean) {
        if (!enabled && !loaded) return
        if (!ensureLoaded()) return
        nativeConfigure(
            enabled,
            config.strideMeters,
            config.maxSpeedMps,
            config.accelerationSeconds,
            config.decelerationSeconds,
            config.sensorNoise,
            config.manualSensorMode,
            config.manualStepFrequencyHz,
            config.joystickSpeedMps,
            config.joystickHeadingDeg,
            config.joystickSensitivity,
            config.motionSpeedMps ?: Float.NaN,
        )
        val f = config.gaitFrame
        val shared = enabled && config.jogProfile.enabled && !config.manualSensorMode && f != null
        nativeConfigureGait(shared, f?.session ?: 0, f?.timestampNs ?: 0,
            f?.speedMps?.toFloat() ?: 0f, f?.cadenceSpm?.div(60)?.toFloat() ?: 0f,
            f?.strideMeters?.toFloat() ?: 0f, f?.phase ?: 0.0, f?.steps ?: 0,
            f?.distanceMeters ?: 0.0, f?.completedMeters ?: 0.0, f?.remainderMeters ?: 0.0)
        val history = if (shared) f!!.recentSteps else emptyList()
        nativeConfigureStepHistory(f?.session ?: 0, LongArray(history.size * 2) { i ->
            if (i % 2 == 0) history[i / 2].index else history[i / 2].timestampNs
        })

    }

    fun rewriteSensorEvent(sensorType: Int, timestampNs: Long, values: FloatArray): Boolean {
        if (sensorType != Sensor.TYPE_ACCELEROMETER &&
            sensorType != Sensor.TYPE_LINEAR_ACCELERATION &&
            sensorType != Sensor.TYPE_GRAVITY &&
            sensorType != Sensor.TYPE_STEP_DETECTOR &&
            sensorType != Sensor.TYPE_STEP_COUNTER
        ) {
            return false
        }
        if (!ensureLoaded()) return false
        return nativeRewriteEvent(sensorType, timestampNs, values)
    }

    fun nativeAttachStatus(): Int {
        if (!ensureLoaded()) return -1
        return nativeAttachSensorQueue()
    }

    fun snapshot(): LongArray {
        if (!ensureLoaded()) return LongArray(6)
        return nativeSnapshot()
    }

    private external fun nativeInit()
    private external fun nativeConfigure(
        enabled: Boolean,
        strideMeters: Float,
        maxSpeedMps: Float,
        accelerationSeconds: Float,
        decelerationSeconds: Float,
        sensorNoise: Float,
        manualMode: Boolean,
        manualStepFrequencyHz: Float,
        joystickSpeedMps: Float,
        joystickHeadingDeg: Float,
        joystickSensitivity: Float,
        actualSpeedMps: Float,
    )

    private external fun nativeConfigureGait(
        enabled: Boolean, session: Long, timestampNs: Long, speedMps: Float,
        frequencyHz: Float, strideMeters: Float, phase: Double, steps: Long,
        distance: Double, completed: Double, remainder: Double,
    )

    private external fun nativeConfigureStepHistory(session: Long, history: LongArray)

    private external fun nativeRewriteEvent(
        sensorType: Int,
        timestampNs: Long,
        values: FloatArray,
    ): Boolean

    private external fun nativeAttachSensorQueue(): Int
    private external fun nativeSnapshot(): LongArray
}
