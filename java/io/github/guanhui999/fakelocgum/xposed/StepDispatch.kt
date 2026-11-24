package io.github.guanhui999.fakelocgum.xposed

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.guanhui999.fakelocgum.core.StepTimeline
import io.github.guanhui999.fakelocgum.core.GaitPlayback
import io.github.guanhui999.fakelocgum.core.stepFrequencyHz
import java.lang.ref.WeakReference
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Produces Android STEP_DETECTOR/STEP_COUNTER callbacks for successful, existing
 * subscriptions. It does not fabricate sensor hardware, grant permissions, or
 * write another application's database. No physical step is needed to drive it.
 */
internal object StepDispatch {
    private const val TAG = "Steps"
    private val entries = WeakHashMap<SensorEventListener, IdentityHashMap<Sensor, Subscription>>()
    private val timer = Handler(Looper.getMainLooper())
    private val timeline = StepTimeline()
    private val gaitPlayback = GaitPlayback()
    private var gaitBase: Long? = null
    private var maximumTotal = 0L
    private var sharedMode = false
    private val synthetic = ThreadLocal<Boolean>()
    private var scheduled = false
    private var logMs = -5_000L
    private val eventConstructor = runCatching {
        SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType).apply { isAccessible = true }
    }.getOrNull()

    private class Subscription(listener: SensorEventListener, val sensor: Sensor, val handler: Handler) {
        val listener = WeakReference(listener)
        val pending = AtomicBoolean(false)
        val registeredNs = SystemClock.elapsedRealtimeNanos()
        @Volatile var active = true
        @Volatile var needsBaseline = true
        @Volatile var lastCounter = -1L
        val detectorTimes = java.util.ArrayDeque<Long>()
        var requestedCounter = 0L
        var requestedTimestamp = 0L
        var previousStepNs = 0L
        var previousDeliveryNs = 0L
        var delivered = 0L
        var errors = 0
    }

    fun isStep(type: Int): Boolean = type == Sensor.TYPE_STEP_DETECTOR || type == Sensor.TYPE_STEP_COUNTER
    fun isSynthetic(): Boolean = synthetic.get() == true

    fun register(listener: SensorEventListener, sensor: Sensor, handler: Handler?) {
        if (!isStep(sensor.type)) return
        if (eventConstructor == null) {
            HookKit.warn(TAG, "SensorEvent(int) unavailable; physical callbacks preserved, timer not installed")
            return
        }
        val entry = Subscription(listener, sensor, handler ?: Handler(Looper.getMainLooper()))
        synchronized(entries) {
            entries.getOrPut(listener) { IdentityHashMap() }.put(sensor, entry)?.active = false
        }
        HookKit.info(TAG, "registered type=${sensor.type} package=${HookState.packageName()} callback=${listener.javaClass.name}")
        schedule(0L)
    }

    fun unregister(listener: SensorEventListener, sensor: Sensor? = null) {
        synchronized(entries) {
            if (sensor == null) entries.remove(listener)?.values?.forEach { it.active = false }
            else {
                val group = entries[listener]
                group?.remove(sensor)?.active = false
                if (group?.isEmpty() == true) entries.remove(listener)
            }
        }
    }

    /** Called at the real listener boundary; true means the timer owns this step stream. */
    fun interceptPhysical(listener: SensorEventListener, event: SensorEvent): Boolean {
        if (isSynthetic() || !isStep(event.sensor.type)) return false
        if (!HookState.sensorEngaged()) return false
        // Some managers synchronously emit the baseline from inside registerListener.
        // Capture it even before register() returns; do not suppress that first call.
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
            timeline.observeHardwareCounter(event.values[0])
        }
        val registered = synchronized(entries) { entries[listener]?.get(event.sensor) } ?: return false
        if (!registered.active) return false
        // No detector value=0 events and no physical+virtual double counting.
        return true
    }

    private val tick = object : Runnable {
        override fun run() {
            synchronized(this@StepDispatch) { scheduled = false }
            val live = synchronized(entries) { entries.values.flatMap { it.values }.filter { it.active } }
            val enabled = HookState.sensorEngaged()
            val nowNs = SystemClock.elapsedRealtimeNanos()
            if (live.isEmpty()) {
                timeline.advance(nowNs, false, 0f)
                return
            }
            val cfg = HookState.config()
            val useShared = cfg.jogProfile.enabled && !cfg.manualSensorMode && cfg.gaitFrame != null
            if (useShared != sharedMode) {
                if (!useShared) timeline.raiseTotal(maximumTotal)
                else gaitBase = maximumTotal - gaitPlayback.read(null, nowNs, false).steps
                sharedMode = useShared
            }
            val frame = if (useShared) {
                val out = gaitPlayback.read(cfg.gaitFrame, nowNs, enabled)
                if (gaitBase == null) gaitBase = timeline.total
                // Capture a synchronous physical counter baseline before first output.
                if (maximumTotal == 0L) gaitBase = maxOf(gaitBase!!, timeline.total)
                StepTimeline.Frame(gaitBase!! + out.steps, out.timestamps, (out.cadenceSpm / 60).toFloat())
            } else timeline.advance(nowNs, enabled, cfg.stepFrequencyHz())
            maximumTotal = maxOf(maximumTotal, frame.total)
            if (!enabled) {
                live.forEach { sub ->
                    sub.needsBaseline = true
                    synchronized(sub) { sub.detectorTimes.clear() }
                }
                schedule(100L)
                return
            }
            for (sub in live) {
                val baselineReady = nowNs - sub.registeredNs >= 250_000_000L
                if (sub.sensor.type == Sensor.TYPE_STEP_COUNTER) {
                    if (baselineReady && (sub.needsBaseline || frame.total != sub.lastCounter)) {
                        dispatch(sub, longArrayOf(frame.timestamps.lastOrNull() ?: nowNs), maximumTotal)
                    }
                } else if (frame.timestamps.isNotEmpty()) {
                    dispatch(sub, frame.timestamps, maximumTotal)
                } else if (frame.frequencyHz <= 0f) {
                    synchronized(sub) {
                        sub.detectorTimes.clear()
                        sub.previousStepNs = 0L
                        sub.previousDeliveryNs = 0L
                    }
                }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - logMs >= 5_000L) {
                logMs = now
                HookKit.info(TAG, "tick hz=${frame.frequencyHz} total=${frame.total} subscriptions=${live.size} delivered=${live.sumOf { it.delivered }}")
            }
            schedule(50L)
        }
    }

    private fun dispatch(sub: Subscription, timestamps: LongArray, total: Long) {
        synchronized(sub) {
            if (sub.sensor.type == Sensor.TYPE_STEP_DETECTOR) {
                for (timestamp in timestamps) {
                    // Retain brief handler stalls without an unbounded queue or resume burst.
                    if (sub.detectorTimes.size >= 12) sub.detectorTimes.removeFirst()
                    sub.detectorTimes.addLast(timestamp)
                }
            } else {
                sub.requestedCounter = total
                sub.requestedTimestamp = timestamps.lastOrNull() ?: SystemClock.elapsedRealtimeNanos()
            }
        }
        if (!sub.pending.compareAndSet(false, true)) return
        val task = object : Runnable {
            override fun run() {
                var requeued = false
                try {
                    val listener = sub.listener.get() ?: return
                    if (!sub.active || !HookState.sensorEngaged()) return
                    val detector = sub.sensor.type == Sensor.TYPE_STEP_DETECTOR
                    val timestamp: Long
                    val counter: Long
                    synchronized(sub) {
                        counter = sub.requestedCounter
                        if (detector) {
                            val oldest = SystemClock.elapsedRealtimeNanos() - 2_000_000_000L
                            while ((sub.detectorTimes.peekFirst() ?: Long.MAX_VALUE) < oldest)
                                sub.detectorTimes.removeFirst()
                            val first = sub.detectorTimes.peekFirst() ?: return
                            if (sub.previousStepNs > 0L) {
                                val spacing = (first - sub.previousStepNs).coerceIn(100_000_000L, 1_500_000_000L)
                                val remaining = sub.previousDeliveryNs + spacing - SystemClock.elapsedRealtimeNanos()
                                if (remaining > 0L) {
                                    requeued = sub.handler.postDelayed(this, (remaining + 999_999L) / 1_000_000L)
                                    return
                                }
                            }
                            timestamp = sub.detectorTimes.removeFirst()
                        } else timestamp = sub.requestedTimestamp
                    }
                    val event = eventConstructor!!.newInstance(1)
                    event.sensor = sub.sensor
                    event.timestamp = timestamp
                    event.accuracy = 3
                    event.values[0] = if (detector) 1f else counter.toFloat()
                    val previous = synthetic.get()
                    synthetic.set(true)
                    try { listener.onSensorChanged(event) } finally { synthetic.set(previous) }
                    sub.delivered++
                    timeline.published()
                    if (!detector) { sub.lastCounter = counter; sub.needsBaseline = false }
                    else {
                        sub.previousStepNs = timestamp
                        sub.previousDeliveryNs = SystemClock.elapsedRealtimeNanos()
                        val next = synchronized(sub) { sub.detectorTimes.peekFirst() }
                        if (next != null && sub.active) {
                            // ONE callback per message, preserving the inter-step spacing.
                            // A busy Looper must not drain 12 events in one batch.
                            val gapMs = ((next - timestamp) / 1_000_000L).coerceIn(100L, 1_500L)
                            requeued = sub.handler.postDelayed(this, gapMs)
                        }
                    }
                } catch (error: Throwable) {
                    if (sub.errors++ < 3) HookKit.error(TAG, "delivery failed type=${sub.sensor.type}", error)
                } finally { if (!requeued) sub.pending.set(false) }
            }
        }
        if (!sub.handler.post(task)) sub.pending.set(false)
    }

    @Synchronized private fun schedule(delay: Long) {
        if (scheduled) return
        scheduled = timer.postDelayed(tick, delay)
    }
}
