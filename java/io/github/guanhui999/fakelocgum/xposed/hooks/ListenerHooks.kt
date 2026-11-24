package io.github.guanhui999.fakelocgum.xposed.hooks

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.Handler
import java.util.concurrent.Executor
import io.github.guanhui999.fakelocgum.xposed.LiveLocationPusher
import io.github.guanhui999.fakelocgum.xposed.SdkDispatch

import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState
import io.github.guanhui999.fakelocgum.xposed.LocationPayload

/**
 * Rewrites the fixes delivered to *subscribed* listeners.
 *
 * [LocationHooks] covers `getLastKnownLocation` (a pull) and the `Location`
 * getters, but a running navigation or map app never calls those — it registers
 * a listener and waits. In AOSP every such registration is funnelled through one
 * private transport class whose callback is what actually reaches the app, so
 * hooking that single class covers every `requestLocationUpdates` /
 * `requestSingleUpdate` overload, present and future, without wrapping the
 * caller's listener.
 *
 * ## Why not wrap the listener instead
 *
 * A `java.lang.reflect.Proxy` in place of the app's listener was considered and
 * rejected, for three reasons that all bite at runtime:
 *
 *  * the framework keeps registrations in a `WeakHashMap` keyed by the listener,
 *    so a proxy nothing else references is collected and the subscription dies;
 *  * a proxy overrides *every* interface method, including the default
 *    `onLocationChanged(List<Location>)`, which makes the framework believe the
 *    listener supports batched delivery even when it only implements the
 *    single-fix callback — and the default body is not a no-op;
 *  * `removeUpdates` matches by equality, so a proxy that is not unwrapped on the
 *    way out leaks a live subscription.
 *
 * The transport sits *below* all of that. The incoming payload is mutated in
 * place rather than swapped, which keeps object identity and every metadata
 * field the app may key off.
 *
 * ## Payload shape
 *
 * The callback argument is not always a bare `Location` — on Android 14 the
 * `ILocationListener` AIDL delivers `List<Location>`, and container shapes exist
 * elsewhere. [LocationPayload] normalises all of them, and reports the first
 * delivery per process into the log, which is how "this path never fires" is told
 * apart from "this path fires and the fix was already real".
 */
internal object ListenerHooks {

    private const val TAG = "Listener"

    private const val PATH = "listener"

    /** AOSP names this transport class consistently, but allow for OEM drift. */
    private val TRANSPORT_CLASSES = arrayOf(
        "android.location.LocationManager\$LocationListenerTransport",
        "android.location.LocationListenerTransport",
    )

    /** Both the single-fix and the batched callback in AOSP 12+. */
    private val CALLBACK_METHODS = arrayOf(
        "onLocationChanged",
        "onLocationChangedAsync",
    )

    fun install(classLoader: ClassLoader) {
        installContinuousSubscriptions(classLoader)
        val transport = HookKit.findClass(classLoader, *TRANSPORT_CLASSES)
        if (transport == null) {
            // Not fatal — but it means live fixes are only rewritten if the
            // getter hooks can reach the reader, which for a native renderer
            // they cannot. The warning names the missing class on purpose.
            HookKit.warn(
                TAG,
                "no LocationListenerTransport on this ROM (${TRANSPORT_CLASSES.joinToString()}); " +
                    "live listener fixes are not rewritten",
            )
            return
        }

        for (methodName in CALLBACK_METHODS) {
            HookKit.hookAllLogged(transport, methodName, TAG) { chain ->
                if (!HookState.active()) return@hookAllLogged chain.proceed()
                chain.args.forEach { argument -> LocationPayload.overwriteAll(argument, PATH) }
                chain.proceed()
            }
        }
    }
    private fun installContinuousSubscriptions(loader: ClassLoader) {
        val manager = HookKit.findClass(loader, "android.location.LocationManager") ?: return
        HookKit.hookAllLogged(manager, "requestLocationUpdates", TAG) { chain ->
            val result = chain.proceed()
            val listener = chain.args.firstOrNull { it is LocationListener } as? LocationListener
            if (listener != null) {
                val callback = LocationListener::class.java.getMethod("onLocationChanged", Location::class.java)
                val executor = chain.args.firstOrNull { it is Executor } as? Executor
                val looper = (chain.args.firstOrNull { it is Looper } as? Looper)
                    ?: (chain.args.firstOrNull { it is Handler } as? Handler)?.looper
                    ?: if (executor == null) Looper.myLooper() else null
                val provider = chain.args.firstOrNull { it is String } as? String ?: LocationManager.GPS_PROVIDER
                SdkDispatch.register(listener, LiveLocationPusher(listener, callback, executor, looper, provider), "platform:$provider")
                HookKit.info("GpsHealth", "requestLocationUpdates succeeded provider=$provider executor=${executor != null} looper=${looper != null}")
            }
            result
        }
        HookKit.hookAllLogged(manager, "removeUpdates", TAG) { chain ->
            // Cancel before queued executor jobs can fire; leave the native subscription untouched.
            chain.args.filterIsInstance<LocationListener>().forEach { SdkDispatch.unregister(it) }
            chain.proceed()
        }
    }

}
