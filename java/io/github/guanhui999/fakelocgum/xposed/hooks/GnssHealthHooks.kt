package io.github.guanhui999.fakelocgum.xposed.hooks

import android.location.GnssStatus
import android.os.Build
import android.os.SystemClock
import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections

/**
 * Version-tolerant, read-only health diagnostics. Never replace registration
 * results or manufacture satellite events. Missing OEM methods are optional.
 */
internal object GnssHealthHooks {
    private const val TAG = "GpsHealth"
    private val installed = Collections.synchronizedSet(HashSet<Method>())
    private val lastMessage = HashMap<String, Long>()

    fun install(loader: ClassLoader) {
        val manager = HookKit.findClass(loader, "android.location.LocationManager") ?: return
        HookKit.info(TAG, "health-only sdk=${Build.VERSION.SDK_INT} maker=${Build.MANUFACTURER} model=${Build.MODEL}")
        HookKit.hookAll(manager, "isProviderEnabled", TAG) { chain ->
            val result = chain.proceed()
            if (HookState.active() && chain.args.firstOrNull() == "gps")
                limited("provider", "system GPS provider enabled=$result (not modified)")
            result
        }
        for (name in arrayOf("registerGnssStatusCallback", "addGpsStatusListener")) {
            HookKit.hookAllLogged(manager, name, TAG) { chain ->
                // Observe the same callbacks the target supplied; do not wrap its identity.
                chain.args.filterNotNull().forEach { candidate ->
                    runCatching { observeCallback(candidate) }.onFailure {
                        limited("arm:$name", "optional status observation unavailable=${it.javaClass.simpleName}")
                    }
                }
                // Original exception/Boolean semantics are left entirely intact.
                val result = chain.proceed()
                if (HookState.active()) {
                    limited("register:$name", "sdk=${Build.VERSION.SDK_INT} package=${HookState.packageName()} $name originalResult=$result")
                }
                result
            }
        }
        for (name in arrayOf("unregisterGnssStatusCallback", "removeGpsStatusListener")) {
            HookKit.hookAll(manager, name, TAG) { chain ->
                val result = chain.proceed()
                if (HookState.active()) limited(name, "target called $name; original call preserved")
                result
            }
        }
    }

    private fun observeCallback(callback: Any) {
        for (name in arrayOf("onStarted", "onStopped", "onFirstFix", "onSatelliteStatusChanged", "onGpsStatusChanged")) {
            val methods = HookKit.methodsNamed(callback.javaClass, name)
                .filterNot { Modifier.isAbstract(it.modifiers) || Modifier.isNative(it.modifiers) }
            if (methods.isEmpty()) continue
            // HookKit hooks a named family. Deduplicate by Method, including inherited methods.
            // any{add} short-circuited before recording all overloads; record the full set.
            val newlyAdded = synchronized(installed) {
                val changed = methods.any { it !in installed }
                installed.addAll(methods)
                changed
            }
            if (!newlyAdded) continue
            HookKit.hookAll(callback.javaClass, name, TAG) { event ->
                if (HookState.active()) runCatching {
                    val gnss = event.args.firstOrNull { it is GnssStatus } as? GnssStatus
                    val detail = if (gnss != null) {
                        val used = (0 until gnss.satelliteCount).count { gnss.usedInFix(it) }
                        val maxCn0 = (0 until gnss.satelliteCount).maxOfOrNull { gnss.getCn0DbHz(it) } ?: 0f
                        "real satellites=${gnss.satelliteCount} usedInFix=$used maxCn0=$maxCn0"
                    } else {
                        "originalValue=${event.args.firstOrNull { it is Number } ?: "none"}"
                    }
                    limited("callback:$name", "$name $detail (not modified)")
                }
                event.proceed()
            }
        }
    }

    @Synchronized
    private fun limited(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val last = lastMessage[key]
        if (last == null || now - last >= 5_000L) {
            lastMessage[key] = now
            HookKit.info(TAG, message)
        }
    }
}
