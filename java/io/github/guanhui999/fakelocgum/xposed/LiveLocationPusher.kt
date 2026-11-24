package io.github.guanhui999.fakelocgum.xposed

import android.location.Location
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deliver NEW fixes to an existing continuous subscription, not only replace getters.
 * No registration is invented; removeUpdates invalidates queued deliveries, and the
 * selected-package gate is checked again on the listener's requested thread.
 */
internal class LiveLocationPusher(
    listener: Any,
    private val callback: Method,
    executor: Executor?,
    looper: Looper?,
    private val provider: String?,
    private val resultFactory: Method? = null,
) : CapturedListener(listener) {
    // Registered delivery owns its dispatcher. A weak executor could vanish and
    // silently stop all future callbacks even though the OS subscription was live.
    private val deliveryExecutor = executor
    private val handler = if (executor == null) Handler(looper ?: Looper.getMainLooper()) else null
    private val queued = AtomicBoolean(false)
    private var reported = false

    override fun deliver(target: Any) {
        if (!queued.compareAndSet(false, true)) return
        val ref = WeakReference(target)
        val task = Runnable {
            try {
                val recipient = ref.get() ?: return@Runnable
                if (!SdkDispatch.isRegistered(recipient, this) || !HookState.active()) return@Runnable
                val sample = LocationSamples.recent(provider)
                val location = HookState.buildLocation(sample, provider)
                // Always fresh timestamps: a visible map drops fixes with a cached timestamp.
                HookState.stampTiming(location)
                val payload = resultFactory?.invoke(null, listOf(location)) ?: location
                LocationSamples.deliver { callback.invoke(recipient, payload) }
                if (!reported) {
                    reported = true
                    HookKit.info("LivePush", "delivered callback=${callback.declaringClass.name}#${callback.name}")
                }
            } catch (error: Throwable) {
                if (!reported) HookKit.error("LivePush", "callback delivery failed ${callback.name}", error)
            } finally { queued.set(false) }
        }
        try {
            val executor = deliveryExecutor
            if (executor != null) executor.execute(task)
            else if (handler?.post(task) != true) queued.set(false)
        } catch (error: Throwable) {
            queued.set(false)
            HookKit.error("LivePush", "listener dispatcher rejected callback", error)
        }
    }
}
