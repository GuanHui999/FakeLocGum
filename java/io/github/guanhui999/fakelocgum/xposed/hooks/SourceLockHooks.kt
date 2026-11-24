package io.github.guanhui999.fakelocgum.xposed.hooks

import io.github.guanhui999.fakelocgum.xposed.Diag
import io.github.guanhui999.fakelocgum.core.NmeaCodec
import java.util.Collections
import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState

/**
 * Denies the target app every *raw* source it could use to work out a real
 * position on its own.
 *
 * ## Why this layer exists at all
 *
 * Every other hook in this module works by rewriting the fix the platform hands
 * over. That is enough only while the app actually asks the platform. A location
 * SDK that ships its own engine can, and in practice does, bypass it:
 *
 *  * it can read raw GNSS (NMEA `GGA` carries latitude and longitude verbatim,
 *    measurements and navigation messages carry everything needed for its own
 *    PVT solution);
 *  * it can build a WiFi/cell fingerprint and ask its own server where those
 *    access points are — a fix computed on somebody else's hardware, which no
 *    amount of hooking inside this process can rewrite.
 *
 * The observable symptom of both is the same and is exactly what this layer is
 * for: the map jumps to the injected position first (that came through the
 * platform, so we rewrote it) and then drifts back to the real one a few seconds
 * later once the app's own engine has an answer.
 *
 * Cutting the inputs is the only interception that holds regardless of how the
 * engine is built, and it is safe to do here because it runs in the *scoped app
 * process only*. The system's own positioning keeps working for every other app.
 *
 * ## What is deliberately left alone
 *
 * | Left intact | Why |
 * |---|---|
 * | `registerGnssStatusCallback`, `addGpsStatusListener` | satellite visibility carries no position — denying it only makes the app declare GPS unusable and fall back to network positioning, which is the opposite of the goal |
 * | `WifiManager.getConnectionInfo` | the fix is already unusable once the scan list is empty; rewriting a `WifiInfo` means fabricating BSSID/SSID strings that some callers parse, and the trade is not worth it |
 * | everything IP-derived | blocking that means blocking the network, which is the app itself |
 */
internal object SourceLockHooks {

    private const val TAG = "Lock"
    private val nmeaClasses = Collections.synchronizedSet(HashSet<Class<*>>())

    private const val WIFI_MANAGER = "android.net.wifi.WifiManager"
    private const val TELEPHONY_MANAGER = "android.telephony.TelephonyManager"
    private const val LOCATION_MANAGER = "android.location.LocationManager"

    /**
     * GNSS entry points that carry real measurements or real coordinates.
     *
     * `GnssMeasurementsEvent` and `GnssNavigationMessage` are the raw input a PVT
     * solver runs on; NMEA is the same data already decoded, with `GGA` holding
     * the position outright. Raw measurement suppression is opt-in. NMEA is registered normally and
     * its coordinate fields are rewritten in the callback, keeping signal health.
     */
    private val RAW_GNSS_ENTRIES = arrayOf(
        "registerGnssMeasurementsCallback",
        "registerGnssNavigationMessageCallback",
        "addGnssBatchingCallback",
    )

    fun install(classLoader: ClassLoader) {
        installFingerprintLock(classLoader)
        installNmeaCoordinates(classLoader)
        installRawGnssLock(classLoader)
    }

    // --------------------------------------------------------- fingerprint

    /**
     * Empties the two fingerprint inputs a server-side positioning request is
     * built from.
     *
     * Empty is a *legitimate* state for both — a device with WiFi scanning off
     * and no visible cells reports exactly this — so nothing downstream has to
     * special-case it, and the app cannot tell "hidden" from "nothing in range".
     */
    private fun installFingerprintLock(classLoader: ClassLoader) {
        HookKit.findClass(classLoader, WIFI_MANAGER)?.let { wifi ->
            denyReturning(wifi, "getScanResults") { emptyList<Any>() }
        }

        HookKit.findClass(classLoader, TELEPHONY_MANAGER)?.let { telephony ->
            denyReturning(telephony, "getAllCellInfo") { emptyList<Any>() }
            denyReturning(telephony, "getNeighboringCellInfo") { emptyList<Any>() }
            denyReturning(telephony, "getCellLocation") { null }
            // The callback flavour of the same query: never proceeding means the
            // callback never fires, which is what "no cell information" looks like.
            denyReturning(telephony, "requestCellInfoUpdate") { null }
        }
    }

    // ------------------------------------------------------------ raw GNSS

    private fun installRawGnssLock(classLoader: ClassLoader) {
        val manager = HookKit.findClass(classLoader, LOCATION_MANAGER) ?: run {
            HookKit.warn(TAG, "$LOCATION_MANAGER not found; raw GNSS not locked")
            return
        }

        for (entry in RAW_GNSS_ENTRIES) {
            val hooked = HookKit.hookAll(manager, entry, TAG) { chain ->
                // Repair: raw data suppression is independent and OFF by default.
                // Navigation apps may use these callbacks as a GPS liveness signal.
                if (!HookState.sourceLockEngaged() || !HookState.config().blockRawGnss) return@hookAll chain.proceed()
                Diag.event(entry, "$entry denied — raw GNSS is not reaching this app")
                true
            }
            if (hooked > 0) {
                HookKit.info(TAG, "$entry optional raw lock armed ($hooked overload(s)); GNSS compatibility default=pass-through")
            }
        }
    }

    /** Register with the real receiver, preserving signal cadence and return status. */
    private fun installNmeaCoordinates(loader: ClassLoader) {
        val manager = HookKit.findClass(loader, LOCATION_MANAGER) ?: return
        HookKit.hookAllLogged(manager, "addNmeaListener", TAG) { chain ->
            for (candidate in chain.args.filterNotNull()) {
                val clazz = candidate.javaClass
                val callbacks = listOf("onNmeaMessage", "onNmeaReceived").filter { name ->
                    HookKit.methodsNamed(clazz, name).any { method ->
                        method.parameterTypes.size == 2 && method.parameterTypes.contains(String::class.java)
                    }
                }
                if (callbacks.isEmpty() || !nmeaClasses.add(clazz)) continue
                callbacks.forEach { name ->
                    HookKit.hookAllLogged(clazz, name, TAG) { event ->
                        if (!HookState.active()) return@hookAllLogged event.proceed()
                        val args = event.args.toMutableList()
                        val index = args.indexOfFirst { it is String }
                        if (index < 0) return@hookAllLogged event.proceed()
                        val cfg = HookState.config()
                        val point = HookState.position()
                        args[index] = NmeaCodec.rewrite(args[index] as String, point.first, point.second,
                            if (HookState.motionEngaged()) HookState.motionSpeed() else if (cfg.useSpeed) cfg.speed else null,
                            if (HookState.motionEngaged()) HookState.motionBearing() else null,
                            if (cfg.useAltitude) cfg.altitude else null)
                        event.proceed(args.toTypedArray())
                    }
                }
            }
            // No fake true, no swallowed registration, no fake satellites/quality.
            chain.proceed()
        }
        HookKit.info(TAG, "GNSS status preserved; NMEA registration passes through with coordinate-only rewrite")
    }

    // ------------------------------------------------------------- plumbing

    /**
     * Installs a hook that answers [reply] instead of calling through, while the
     * source lock is engaged.
     *
     * [HookKit.hookAll] rather than [HookKit.hookAllLogged] on purpose: the
     * deprecated members below are absent on newer releases, and a warning for a
     * method that this ROM legitimately does not have is noise that buries the
     * warnings that matter. Presence is reported positively instead.
     */
    private inline fun denyReturning(
        clazz: Class<*>,
        methodName: String,
        crossinline reply: () -> Any?,
    ) {
        val hooked = HookKit.hookAll(clazz, methodName, TAG) { chain ->
            if (!HookState.sourceLockEngaged()) return@hookAll chain.proceed()
            Diag.event(methodName, "$methodName denied — no fingerprint is leaving this app")
            reply()
        }
        if (hooked > 0) {
            HookKit.info(TAG, "$methodName denied ($hooked overload(s))")
        }
    }
}
