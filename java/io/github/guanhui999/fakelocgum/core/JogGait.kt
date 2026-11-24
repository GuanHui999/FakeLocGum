package io.github.guanhui999.fakelocgum.core

import kotlin.math.*

/** All jogging parameters are persisted, independently of runtime samples. */
data class JogProfile(
    val enabled: Boolean = true,
    val cadenceSpm: Double = 160.0,
    val cadenceMinSpm: Double = 152.0,
    val cadenceMaxSpm: Double = 168.0,
    val strideMeters: Double = 1.12,
    val strideVariation: Double = 0.06,
    val speedElasticity: Double = 0.45,
    val rampUpSeconds: Double = 6.0,
    val rampDownSeconds: Double = 2.0,
    val fluctuationSeconds: Double = 3.0,
    val seed: Long = 73471L,
) {
    val referenceSpeed: Double get() = cadenceSpm / 60.0 * strideMeters
    fun validated(): JogProfile {
        fun sane(v: Double, fallback: Double, low: Double, high: Double) =
            (if (v.isFinite()) v else fallback).coerceIn(low, high)
        val base = sane(cadenceSpm, 160.0, 80.0, 220.0)
        return copy(cadenceSpm = base,
            cadenceMinSpm = sane(cadenceMinSpm, 152.0, 60.0, base),
            cadenceMaxSpm = sane(cadenceMaxSpm, 168.0, base, 240.0),
            strideMeters = sane(strideMeters, 1.12, 0.3, 2.0),
            strideVariation = sane(strideVariation, 0.06, 0.0, 0.15),
            speedElasticity = sane(speedElasticity, 0.45, 0.1, 0.8),
            rampUpSeconds = sane(rampUpSeconds, 6.0, 0.5, 20.0),
            rampDownSeconds = sane(rampDownSeconds, 2.0, 0.2, 10.0),
            fluctuationSeconds = sane(fluctuationSeconds, 3.0, 0.5, 15.0))
    }
}

data class GaitStep(val index: Long, val timestampNs: Long, val lengthMeters: Double)
data class GaitFrame(
    val session: Long = 0,
    val timestampNs: Long = 0,
    val speedMps: Double = 0.0,
    val cadenceSpm: Double = 0.0,
    val strideMeters: Double = 0.0,
    val phase: Double = 0.0,
    val steps: Long = 0,
    val distanceMeters: Double = 0.0,
    val completedMeters: Double = 0.0,
    val remainderMeters: Double = 0.0,
    val elapsedSeconds: Double = 0.0,
    val recentSteps: List<GaitStep> = emptyList(),
) {
    /** Average of completed footfalls, excluding the explicitly recorded partial step. */
    val averageStride: Double get() = if (steps > 0) completedMeters / steps else 0.0
    val effectiveSteps: Double get() = steps + phase
    val accountingError: Double get() = distanceMeters - completedMeters - remainderMeters
}

/**
 * Manager owns ONE gait ledger for GPS, Java callbacks and Gum. Randomness is
 * low-frequency, bounded and seeded; stride is derived from v/f, never independently
 * randomized. Each landing integrates the distance actually travelled since the last.
 */
class JogGait(private val session: Long) {
    private var lastNs = -1L
    private var startNs = -1L
    private var phase = 0.0
    private var distance = 0.0
    private var completed = 0.0
    private var partial = 0.0
    private var count = 0L
    private var elapsed = 0.0
    private val history = java.util.ArrayDeque<GaitStep>()
    private var frame = GaitFrame(session = session)

    /** Pause transport without integrating unobserved movement on the next update. */
    fun pause(nowNs: Long) { lastNs = nowNs }

    /** Normal stop settles the last sub-step into the final stride, not an invented step. */
    fun finish(nowNs: Long): GaitFrame {
        if (count > 0 && partial > 0.0) {
            val last = history.removeLast()
            history.addLast(last.copy(lengthMeters = last.lengthMeters + partial))
            completed += partial
            partial = 0.0
            phase = 0.0
        }
        lastNs = nowNs
        frame = frame.copy(timestampNs = nowNs, speedMps = 0.0, cadenceSpm = 0.0, strideMeters = 0.0,
            completedMeters = completed, remainderMeters = partial, phase = phase, recentSteps = history.toList())
        return frame
    }

    fun sample(nowNs: Long, speedMps: Double, profile: JogProfile): GaitFrame {
        val p = profile.validated()
        val speed = if (speedMps.isFinite()) speedMps.coerceIn(0.0, 4.5) else 0.0
        if (lastNs < 0) { lastNs = nowNs; startNs = nowNs }
        if (nowNs < lastNs) return frame
        val gap = nowNs - lastNs
        // No invented travel/steps over suspension. Controller follows the same rule.
        val span = if (gap > 1_000_000_000L) 0L else gap
        val from = nowNs - span
        var t = from
        while (t < nowNs && span > 0) {
            val end = minOf(t + 10_000_000L, nowNs)
            val dt = (end - t) / 1e9
            val hz = cadence(speed, (t + (end - t) / 2 - startNs) / 1e9, p) / 60.0
            elapsed += dt
            if (speed > 0.0 && hz > 0.0) {
                val turns = hz * dt
                val metres = speed * dt
                if (phase + turns >= 1.0) {
                    val fraction = (1.0 - phase) / turns
                    val beforeLanding = metres * fraction
                    partial += beforeLanding
                    distance += beforeLanding
                    completed += partial
                    count++
                    val stamp = t + ((end - t) * fraction).roundToLong()
                    history.addLast(GaitStep(count, stamp, partial))
                    while (history.size > 64) history.removeFirst()
                    partial = metres - beforeLanding
                    distance += partial
                    phase = phase + turns - 1.0
                } else {
                    phase += turns
                    partial += metres
                    distance += metres
                }
            }
            t = end
        }
        lastNs = nowNs
        val cadence = cadence(speed, (nowNs - startNs) / 1e9, p)
        frame = GaitFrame(session, nowNs, speed, cadence,
            if (cadence > 0) speed * 60 / cadence else 0.0, phase, count,
            distance, completed, partial, elapsed, history.toList())
        return frame
    }

    private fun cadence(speed: Double, seconds: Double, p: JogProfile): Double {
        if (speed <= 0.0001) return 0.0
        val ratio = speed / p.referenceSpeed
        val nominal = p.cadenceSpm * ratio.pow(p.speedElasticity)
        val unit = smoothNoise(seconds / p.fluctuationSeconds, p.seed)
        val bound = if (unit >= 0) p.cadenceMaxSpm - p.cadenceSpm else p.cadenceSpm - p.cadenceMinSpm
        val adjusted = nominal * (1.0 + unit * bound / p.cadenceSpm)
        // Keep stride fluctuation bounded too, without violating speed=f*stride.
        val low = nominal / (1.0 + p.strideVariation)
        val high = nominal / (1.0 - p.strideVariation)
        val bounded = adjusted.coerceIn(low, high).coerceIn(0.0, 360.0)
        // 160*1.12/60 = 2.9867m/s. Allow the rounded 3m/s target without
        // rounding noise nudging the explicitly configured 152..168 bounds.
        return if (ratio in 0.98..1.02) bounded.coerceIn(p.cadenceMinSpm, p.cadenceMaxSpm) else bounded
    }

    private fun smoothNoise(x: Double, seed: Long): Double {
        val cell = floor(x).toLong()
        val u = x - floor(x)
        val fade = u * u * (3.0 - 2.0 * u)
        fun value(i: Long): Double {
            var n = (i xor seed) * 6364136223846793005L + 1442695040888963407L
            n = (n xor (n ushr 33)) * -49064778989728563L
            n = n xor (n ushr 29)
            return (n ushr 11).toDouble() / 9007199254740991.0 * 2.0 - 1.0
        }
        return value(cell) * (1.0 - fade) + value(cell + 1) * fade
    }
}
