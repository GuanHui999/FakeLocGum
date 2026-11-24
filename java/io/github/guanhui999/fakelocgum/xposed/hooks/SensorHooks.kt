package io.github.guanhui999.fakelocgum.xposed.hooks

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.os.Handler
import io.github.guanhui999.fakelocgum.engine.MotionBridge
import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState
import io.github.guanhui999.fakelocgum.xposed.StepDispatch
import java.lang.reflect.Method
import java.util.Collections

/** Register once, preserve original permissions/result, and intercept typed listener events. */
internal object SensorHooks {
    private const val TAG = "Sensor"
    private val armed = Collections.synchronizedSet(HashSet<Method>())

    fun install(classLoader: ClassLoader) {
        val manager = HookKit.findClass(classLoader, "android.hardware.SystemSensorManager", "android.hardware.SensorManager")
            ?: return HookKit.warn(TAG, "SensorManager unavailable; no sensor subscriptions captured")
        // SystemSensorManager funnels every public overload into this implementation.
        // Hook one level only, not both registerListener and registerListenerImpl.
        val register = if (HookKit.methodsNamed(manager, "registerListenerImpl").isNotEmpty())
            "registerListenerImpl" else "registerListener"
        HookKit.hookAllLogged(manager, register, TAG) { chain ->
            val listener = chain.args.firstOrNull { it is SensorEventListener } as? SensorEventListener
            val sensor = chain.args.firstOrNull { it is Sensor } as? Sensor
            val handler = chain.args.firstOrNull { it is Handler } as? Handler
            val ready = listener?.let { runCatching { arm(it.javaClass) }.getOrDefault(false) } ?: false
            // No registration success or ACTIVITY_RECOGNITION grant is invented.
            val result = chain.proceed()
            if (listener != null && sensor != null && result == true && ready) {
                runCatching { StepDispatch.register(listener, sensor, handler) }.onFailure {
                    HookKit.error(TAG, "step timer unavailable; successful physical subscription retained", it)
                }
                if (HookState.sensorEngaged()) HookKit.info(TAG,
                    "subscription accepted type=${sensor.type} name=${sensor.name} package=${HookState.packageName()}")
            } else if (listener != null && sensor != null && HookState.sensorEngaged()) {
                HookKit.warn(TAG, "subscription NOT captured type=${sensor.type} accepted=$result hookReady=$ready; check activity-recognition permission")
            }
            result
        }
        val unregister = if (HookKit.methodsNamed(manager, "unregisterListenerImpl").isNotEmpty())
            "unregisterListenerImpl" else "unregisterListener"
        HookKit.hookAllLogged(manager, unregister, TAG) { chain ->
            val listener = chain.args.firstOrNull { it is SensorEventListener } as? SensorEventListener
            val sensor = chain.args.firstOrNull { it is Sensor } as? Sensor
            if (listener != null) StepDispatch.unregister(listener, sensor)
            chain.proceed()
        }
        HookKit.info(TAG, "typed sensor callbacks + continuous step stream installed; no raw-array-as-accelerometer fallback")
    }

    private fun arm(clazz: Class<*>): Boolean {
        val method = HookKit.findMethod(clazz, "onSensorChanged", SensorEvent::class.java) ?: return false
        synchronized(armed) {
            if (method in armed) return true
            val count = HookKit.hookAll(method.declaringClass, "onSensorChanged", TAG) { chain ->
                val listener = chain.thisObject as? SensorEventListener
                val event = chain.args.firstOrNull() as? SensorEvent
                if (listener == null || event == null || StepDispatch.isSynthetic() || !HookState.sensorEngaged()) {
                    return@hookAll chain.proceed()
                }
                if (StepDispatch.interceptPhysical(listener, event)) return@hookAll null
                // Use Sensor.type from the delivered object; a queue integer is a handle,
                // not a sensor type. Light/rotation/magnetic events are left untouched.
                if (!StepDispatch.isStep(event.sensor.type)) {
                    runCatching { MotionBridge.rewriteSensorEvent(event.sensor.type, event.timestamp, event.values) }
                        .onFailure { if (HookKit.firstTime("sensor-jni-failed")) HookKit.error(TAG, "JNI sensor rewrite failed; physical event preserved", it) }
                }
                chain.proceed()
            }
            if (count > 0) armed.add(method)
            return count > 0
        }
    }
}
