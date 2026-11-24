package io.github.guanhui999.fakelocgum.xposed.hooks

import android.location.GnssStatus
import android.os.SystemClock
import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState
import io.github.guanhui999.fakelocgum.xposed.LocationSamples
import java.util.Collections

/**
 * Observe actual receiver health without changing results, enabling GPS, or
 * inventing satellites. This differentiates a receiver stop from a map rejecting
 * incomplete simulated fixes, across vendors and Android versions.
 */
internal object GpsHealthHooks {
    private const val TAG = "GpsHealth"
    private val callbackClasses = Collections.synchronizedSet(HashSet<Class<*>>())
    @Volatile private var lastSatelliteLogMs = -5_000L

    fun install(loader: ClassLoader) {
        val manager = HookKit.findClass(loader, "android.location.LocationManager") ?: return
        for (name in arrayOf("registerGnssStatusCallback", "addGpsStatusListener", "addNmeaListener",
            "registerGnssMeasurementsCallback", "unregisterGnssStatusCallback", "removeGpsStatusListener",
            "removeNmeaListener", "unregisterGnssMeasurementsCallback")) {
            HookKit.hookAll(manager, name, TAG) { chain ->
                // Diagnostics must never prevent a receiver registration on an OEM ROM.
                runCatching {
                    if (name == "registerGnssStatusCallback") {
                        chain.args.filterIsInstance<GnssStatus.Callback>().forEach { arm(it.javaClass) }
                    }
                }
                // Always run the original API exactly once, even for unselected apps.
                val result = chain.proceed()
                runCatching {
                    if (HookState.active()) HookKit.info(TAG,
                        "api=$name result=$result package=${HookState.packageName()} rawLock=${HookState.config().blockRawGnss}")
                }
                result
            }
        }
        // The callback implementation can inherit no-op base methods or override them.
        arm(GnssStatus.Callback::class.java)
    }

    private fun arm(clazz: Class<*>) {
        if (!callbackClasses.add(clazz)) return
        for (name in arrayOf("onStarted", "onStopped", "onFirstFix", "onSatelliteStatusChanged")) {
            // Only declared overrides; inherited methods are already hooked on the base.
            if (runCatching { clazz.declaredMethods.none { it.name == name } }.getOrDefault(true)) continue
            HookKit.hookAll(clazz, name, TAG) { chain ->
                runCatching {
                if (HookState.active()) {
                    val now = SystemClock.elapsedRealtime()
                    val status = chain.args.firstOrNull() as? GnssStatus
                    if (status != null) {
                        if (now - lastSatelliteLogMs >= 5_000L) {
                            lastSatelliteLogMs = now
                            val used = (0 until status.satelliteCount).count { status.usedInFix(it) }
                            HookKit.info(TAG, "receiver satellites=${status.satelliteCount} usedInFix=$used package=${HookState.packageName()}")
                        }
                    } else {
                        HookKit.info(TAG, "receiver event=$name firstFixMs=${chain.args.firstOrNull()} package=${HookState.packageName()}")
                        if (name == "onStopped") LocationSamples.invalidate("gps")
                    }
                }
                }
                chain.proceed()
            }
        }
    }
}
