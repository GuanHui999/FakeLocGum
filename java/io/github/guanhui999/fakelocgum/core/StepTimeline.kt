package io.github.guanhui999.fakelocgum.core

import kotlin.math.floor

/** Cadence in steps/second. Automatic mode follows actual integrated velocity. */
fun SpoofConfig.stepFrequencyHz(): Float {
    if (!spoofs()) return 0f
    if (jogProfile.enabled && !manualSensorMode) gaitFrame?.let { return (it.cadenceSpm / 60.0).toFloat() }
    val frequency = if (manualSensorMode) manualStepFrequencyHz else {
        val actual = if (joystickSpeedMps <= 0f) 0f
            else motionSpeedMps ?: (joystickSpeedMps * joystickSensitivity)
        actual.coerceIn(0f, maxSpeedMps) / strideMeters.coerceAtLeast(0.1f)
    }
    return if (frequency.isFinite()) frequency.coerceIn(0f, 6f) else 0f
}

/**
 * One clock shared by all subscribers, independent of real sensor-event arrivals.
 * Bounded catch-up prevents a suspended app from delivering a large burst on resume.
 */
class StepTimeline {
    data class Frame(val total: Long, val timestamps: LongArray, val frequencyHz: Float)
    private var lastNs = -1L
    private var fraction = 0.0
    private var generated = 0L
    private var baseline = 0L
    private var baselineKnown = false
    private var published = false
    val total: Long get() = baseline + generated

    /** Anchor to the first physical counter, but never jump a published virtual counter. */
    @Synchronized fun observeHardwareCounter(value: Float) {
        if (!baselineKnown && !published && value.isFinite() && value >= 0f) {
            baseline = value.toLong()
            baselineKnown = true
        }
    }

    @Synchronized fun raiseTotal(minimum: Long) {
        if (minimum > total) baseline += minimum - total
    }

    @Synchronized fun published() { published = true }

    @Synchronized fun advance(nowNs: Long, enabled: Boolean, requestedHz: Float): Frame {
        val hz = if (enabled && requestedHz.isFinite()) requestedHz.coerceIn(0f, 6f) else 0f
        if (lastNs < 0L || !enabled || hz <= 0f) {
            lastNs = nowNs
            fraction = 0.0
            return Frame(total, longArrayOf(), hz)
        }
        if (nowNs <= lastNs) return Frame(total, longArrayOf(), hz)
        val previous = lastNs
        lastNs = nowNs
        val elapsed = nowNs - previous
        if (elapsed > 1_000_000_000L) {
            fraction = 0.0
            return Frame(total, longArrayOf(), hz)
        }
        val before = fraction
        val accumulated = before + elapsed.toDouble() / 1_000_000_000.0 * hz
        val steps = floor(accumulated + 1e-9).toInt().coerceAtMost(6)
        fraction = (accumulated - steps).coerceIn(0.0, 0.999999999)
        val timestamps = LongArray(steps) { i ->
            (previous + ((i + 1.0 - before) / hz * 1_000_000_000.0).toLong()).coerceIn(previous + 1, nowNs)
        }
        generated += steps
        return Frame(total, timestamps, hz)
    }
}
