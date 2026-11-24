package io.github.guanhui999.fakelocgum.engine

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.guanhui999.fakelocgum.core.Geo
import io.github.guanhui999.fakelocgum.core.JogGait
import io.github.guanhui999.fakelocgum.core.moveTo
import io.github.guanhui999.fakelocgum.data.DeliveryState
import io.github.guanhui999.fakelocgum.data.RemoteStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.*

data class JoystickStatus(val message: String = "推住方向持续移动", val speed: Float = 0f,
    val distanceMetres: Double = 0.0, val heading: Float = 0f)

/** Held input is integrated by a timer, not by the frequency of ACTION_MOVE. */
class JoystickController(private val store: RemoteStore) {
    private val handler = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var running = false
    private var lastMs = 0L
    private var retryMs = -1_000L
    private var logMs = -1_000L
    private var speed = 0f
    private var distance = 0.0
    private var gait: JogGait? = null
    private var releasing = false
    private var headingRad = 0.0
    private var gaitDistance = 0.0
    private var wasJogging = false
    private val telemetry = MutableStateFlow(JoystickStatus())
    val status = telemetry.asStateFlow()

    fun input(east: Float, screenDown: Float) {
        if (!east.isFinite() || !screenDown.isFinite()) { stop(); return }
        if (!store.load().spoofs()) {
            stop(); telemetry.value = telemetry.value.copy(message = "请先选择目标并开启模拟"); return
        }
        x = east.coerceIn(-1f, 1f)
        y = screenDown.coerceIn(-1f, 1f)
        val length = hypot(x.toDouble(), y.toDouble()).toFloat()
        if (length > 1f) { x /= length; y /= length }
        if (length < 0.04f) {
            if (running && store.load().jogProfile.enabled && !store.load().manualSensorMode) {
                releasing = true // normal release eases out; stop() remains an emergency stop
                return
            }
            stop(); return
        }
        releasing = false
        headingRad = atan2(-y.toDouble(), x.toDouble())
        if (!running) {
            running = true
            distance = 0.0
            lastMs = SystemClock.elapsedRealtime()
            gaitDistance = 0.0
            wasJogging = store.load().jogProfile.enabled && !store.load().manualSensorMode
            gait = JogGait(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)).also {
                it.sample(SystemClock.elapsedRealtimeNanos(), 0.0, store.load().jogProfile)
            }
            Log.i(TAG, "input started x=$x y=$y; held input timer armed")
            handler.post(tick)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val cfg = store.load()
            if (!cfg.spoofs()) { stop(); return }
            val now = SystemClock.elapsedRealtime()
            val rawDt = (now - lastMs) / 1000f
            val dt = if (rawDt > 1f) 0f else rawDt.coerceIn(0f, 0.25f)
            lastMs = now
            if (!store.serviceAvailable || store.delivery.value == DeliveryState.FAILED) {
                // Repair: keep a held gesture alive across transient Binder failure.
                // Pause integration so a reconnection does not teleport the accumulated route.
                speed = 0f
                gait?.pause(SystemClock.elapsedRealtimeNanos())
                if (now - retryMs >= 1_000L) {
                    retryMs = now
                    if (store.serviceAvailable) store.reconnect()
                    Log.w(TAG, "motion paused; remote unavailable, retrying while finger held")
                }
                telemetry.value = telemetry.value.copy(message = "等待配置连接，暂不移动", speed = 0f)
                handler.postDelayed(this, 100L)
                return
            }
            if (cfg.maxSpeedMps <= 0f) {
                speed = 0f
                gait?.pause(SystemClock.elapsedRealtimeNanos())
                store.updateMotion { it.copy(joystickSpeedMps = 0f, motionSpeedMps = 0f,
                    gaitFrame = it.gaitFrame?.copy(timestampNs = SystemClock.elapsedRealtimeNanos(), speedMps = 0.0, cadenceSpm = 0.0, strideMeters = 0.0)) }
                telemetry.value = telemetry.value.copy(message = "速度上限为 0，请在首页调高", speed = 0f)
                handler.postDelayed(this, 100L)
                return
            }
            val magnitude = hypot(x.toDouble(), y.toDouble()).toFloat().coerceIn(0f, 1f)
            val requested = if (releasing) 0f else magnitude * cfg.maxSpeedMps
            val target = (requested * cfg.joystickSensitivity).coerceAtMost(cfg.maxSpeedMps)
            val jogging = cfg.jogProfile.enabled && !cfg.manualSensorMode
            val profile = cfg.jogProfile.validated()
            if (jogging != wasJogging) {
                gait = JogGait(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)).also {
                    it.sample(SystemClock.elapsedRealtimeNanos(), 0.0, profile)
                }
                gaitDistance = 0.0
                wasJogging = jogging
            }
            val duration = if (jogging) {
                (if (target > speed) profile.rampUpSeconds else profile.rampDownSeconds).toFloat()
            } else if (target > speed) cfg.accelerationSeconds else cfg.decelerationSeconds
            val step = cfg.maxSpeedMps * dt / duration.coerceAtLeast(0.2f)
            speed += (target - speed).coerceIn(-step, step)
            val angle = headingRad
            val frame = if (jogging) gait?.sample(SystemClock.elapsedRealtimeNanos(), speed.toDouble(), profile) else null
            val travelled = if (frame != null) (frame.distanceMeters - gaitDistance).coerceAtLeast(0.0) else (speed * dt).toDouble()
            if (frame != null) gaitDistance = frame.distanceMeters
            val heading = Math.toDegrees(angle).toFloat()
            store.updateMotion { current ->
                if (!current.spoofs()) current else {
                    val (lat, lon) = Geo.offsetMetres(current.latitude, current.longitude,
                        cos(angle) * travelled, sin(angle) * travelled)
                    // Preserve the existing settings/config/MotionBridge wire protocol.
                    (if (travelled > 0) current.moveTo(lat, lon) else current).copy(joystickSpeedMps = if (releasing && speed > 0f) speed else requested, motionSpeedMps = speed,
                        joystickHeadingDeg = heading, gaitFrame = frame)
                }
            }
            distance += travelled
            telemetry.value = JoystickStatus("移动中：上北下南，左西右东", speed, distance, heading)
            if (now - logMs >= 1_000L) {
                logMs = now
                Log.i(TAG, "integrated distanceM=$distance speedMps=$speed headingDeg=$heading delivery=${store.delivery.value}")
            }
            if (releasing && speed <= 0.001f) { stop(); return }
            handler.postDelayed(this, 100L)
        }
    }

    fun stop() {
        val ownedMotion = running
        running = false
        handler.removeCallbacks(tick)
        x = 0f; y = 0f; speed = 0f; releasing = false
        if (ownedMotion) {
            val finalFrame = if (wasJogging) gait?.finish(SystemClock.elapsedRealtimeNanos()) else null
            store.updateMotion { it.copy(joystickSpeedMps = 0f, motionSpeedMps = 0f, gaitFrame = finalFrame) }
            Log.i(TAG, "input stopped distanceM=$distance; neutral published")
        }
        telemetry.value = telemetry.value.copy(message = "已停止 · 上北下南，左西右东", speed = 0f)
    }

    private companion object { const val TAG = "FakeLocGum/Joystick" }
}
