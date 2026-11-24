package io.github.guanhui999.fakelocgum.xposed

import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import io.github.guanhui999.fakelocgum.core.ConfigCodec
import io.github.guanhui999.fakelocgum.core.Coords
import io.github.guanhui999.fakelocgum.core.Datum
import io.github.guanhui999.fakelocgum.core.Geo
import io.github.guanhui999.fakelocgum.core.Keys
import io.github.guanhui999.fakelocgum.core.SpoofConfig
import io.github.guanhui999.fakelocgum.engine.MotionBridge
import java.util.Locale

/**
 * Everything the hooks need to know, resolved inside the *hooked* process.
 *
 * Lives in the target app's address space, so it must be a cheap, thread-safe
 * read path: [config] hands back an immutable snapshot decoded once, not a fresh
 * parse per `Location.getLatitude()` call. Updates arrive two ways, on purpose:
 *
 *  1. an `OnSharedPreferenceChangeListener` on the LSPosed remote preferences,
 *     which is the documented push channel, and
 *  2. a slow polling fallback ([RELOAD_INTERVAL_MS]), in case a manager build
 *     fails to deliver the change notification. Without it a stale snapshot
 *     would silently pin the module to old coordinates.
 *
 * The listener is held in a field rather than created inline: `SharedPreferences`
 * only keeps a weak reference to listeners, so a local one would be collected
 * and stop firing.
 */
internal object HookState {

    private const val TAG = "State"
    private const val RELOAD_INTERVAL_MS = 2_000L

    /**
     * Set to `true` to trace every interception through the LSPosed log. Off by
     * default — a hooked `getLatitude()` fires thousands of times a second and
     * would drown the log.
     */
    @Volatile
    var verbose: Boolean = false

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    private var current: SpoofConfig = SpoofConfig()

    @Volatile
    private var lastReloadMs: Long = 0L

    /** Package name of the process we are loaded into, exactly as reported. */
    @Volatile
    private var processPackage: String? = null

    @Volatile private var injectedProcess: String? = null
    private var preferenceSource: (() -> SharedPreferences)? = null
    private var lastRaw: String? = null
    private var lastRevision: String? = null
    private var lastSummary = ""
    private var nativeAttached = false
    private var lastNativeAttempt = 0L
    private val timestampLock = Any()
    private var lastOutputNs = 0L
    @Volatile private var lastMotionMs = -10_000L
    private val sharedProcesses = setOf("android", "system", "com.android.providers.settings",
        "com.android.phone", "com.google.android.gms", "io.github.guanhui999.fakelocgum")

    fun bindProcess(name: String?) { injectedProcess = name }
    fun processName(): String? = injectedProcess
    fun hasPreferences(): Boolean = preferences != null

    @Synchronized
    fun connect(packageName: String?, source: () -> SharedPreferences) {
        // Later package announcements in a shared process cannot change its owner.
        if (processPackage == null) processPackage = packageName
        preferenceSource = source
        reload()
    }

    @Synchronized
    fun initFallback(packageName: String?) {
        if (processPackage == null) processPackage = packageName
        if (preferences == null) current = SpoofConfig()
    }

    private val driftEngine = Geo.DriftEngine()

    private val changeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == Keys.CONFIG || key == Keys.REVISION) {
                reload()
                if (verbose) HookKit.info(TAG, "config reloaded after remote change")
            }
        }

    /** A stable listener reference is retained; detach before replacing a handle. */
    @Synchronized
    fun init(sharedPreferences: SharedPreferences, packageName: String?) {
        if (processPackage == null) processPackage = packageName
        if (preferences !== sharedPreferences) {
            preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(changeListener) } }
            preferences = sharedPreferences
            runCatching { sharedPreferences.registerOnSharedPreferenceChangeListener(changeListener) }
                .onFailure { HookKit.error(TAG, "prefs listener unavailable; polling remains enabled", it) }
        }
        lastRaw = null
        reload()
    }

    /** Repair: polling must reacquire a missing/dead handle, not decode null forever. */
    @Synchronized
    fun reload() {
        lastReloadMs = SystemClock.elapsedRealtime()
        try {
            if (preferences == null) {
                val acquired = preferenceSource?.invoke()
                if (acquired == null) {
                    current = SpoofConfig()
                    return
                }
                preferences = acquired
                runCatching { acquired.registerOnSharedPreferenceChangeListener(changeListener) }
                    .onFailure { HookKit.error(TAG, "listener registration failed; polling enabled", it) }
                lastRaw = null
                HookKit.info(TAG, "remote bound group=${Keys.REMOTE_GROUP} package=$processPackage")
            }
            val raw = preferences?.getString(Keys.CONFIG, null)
            val revision = preferences?.getString(Keys.REVISION, null)
            if (raw != lastRaw || revision != lastRevision || raw == null) {
                // Distinguish malformed JSON from a genuine user STOP, without logging keys/coordinates.
                if (!raw.isNullOrBlank()) JSONObject(raw)
                val previous = current
                current = ConfigCodec.decode(raw)
                if (previous.joystickSpeedMps > 0f || current.joystickSpeedMps > 0f)
                    lastMotionMs = SystemClock.elapsedRealtime()
                if (previous.latitude != current.latitude || previous.longitude != current.longitude ||
                    previous.playing != current.playing || previous.joystickSpeedMps != current.joystickSpeedMps) {
                    // Async delivery only: do not invoke app code while holding the config lock.
                    SdkDispatch.configurationChanged()
                }
                lastRaw = raw
                lastRevision = revision
                runCatching { MotionBridge.configure(current, applies(current)) }
                    .onFailure { HookKit.error(TAG, "native unavailable; Java location hooks remain active", it) }
            }
            // Preserve the original frida-gum queue attachment, without making it
            // a prerequisite for the independent Java location callback hooks.
            if (applies(current) && !nativeAttached && SystemClock.elapsedRealtime() - lastNativeAttempt >= 10_000L) {
                lastNativeAttempt = SystemClock.elapsedRealtime()
                runCatching { MotionBridge.nativeAttachStatus() }
                    .onSuccess { code -> nativeAttached = code >= 0; HookKit.info(TAG, "native queue attach status=$code") }
                    .onFailure { HookKit.error(TAG, "native queue unavailable; Java hooks continue", it) }
            }
            val summary = selfCheck()
            if (summary != lastSummary) {
                HookKit.info(TAG, "config received $summary")
                lastSummary = summary
            }
        } catch (error: Throwable) {
            // Unknown configuration must never broaden scope or remain permanently enabled.
            current = SpoofConfig()
            preferences?.let { runCatching { it.unregisterOnSharedPreferenceChangeListener(changeListener) } }
            preferences = null
            lastRaw = null
            runCatching { MotionBridge.configure(current, false) }
            HookKit.error(TAG, "remote read failed; disabled until next retry group=${Keys.REMOTE_GROUP}", error)
        }
    }

    /**
     * One-line summary of the switches that decide whether anything happens here.
     *
     * Written at startup because those values are invisible everywhere else: a
     * map-SDK channel can arm perfectly and still rewrite nothing, because
     * `mapSdkCompat` is off or the app's own start/stop switch is — and in the
     * log that is indistinguishable from a channel that never armed at all. This
     * line is what separates "the hook is not installed" from "the hook is
     * installed but not allowed to fire".
     *
     * The anchor is printed on purpose: it is the coordinate the user picked, not
     * the device's real position, so the line stays safe to paste into a report.
     */
    fun selfCheck(): String = buildString {
        val cfg = current
        append("process=").append(injectedProcess ?: "<unknown>")
        append(" package=").append(processPackage ?: "<unknown>")
        append(" matched=").append(cfg.matchesPackage(processPackage))
        append(" enabled=").append(cfg.playing)
        append(" active=").append(applies(cfg))
        append(" prefs=").append(preferences != null)
        append(" mapSdk=").append(cfg.mapSdkCompat)
        append(" strictSources=").append(cfg.strictSources)
    }

    /** Immutable snapshot of the active configuration. */
    fun config(): SpoofConfig {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReloadMs >= RELOAD_INTERVAL_MS) {
            reload()
        }
        return current
    }

    /**
     * Whether this process should be spoofed right now.
     *
     * LSPosed scope determines which processes can run the module. The package
     * selected in the manager narrows that scope again, so a loaded module in a
     * non-target process remains a transparent pass-through.
     */
    private fun applies(cfg: SpoofConfig): Boolean {
        if (!cfg.spoofs()) return false
        val process = processPackage?.substringBefore(':') ?: return false
        // Shared providers/system_server must never spoof unselected callers globally.
        if (process in sharedProcesses) return false
        return cfg.matchesPackage(process)
    }

    /** `true` when this process should receive a fake fix right now. */
    fun active(): Boolean = applies(config())

    /** `true` when mock-location traces should be scrubbed. */
    fun antiMockEngaged(): Boolean = config().let { it.antiMock && applies(it) }

    /** Sensor callbacks and native queue rewriting share the location gate. */
    fun sensorEngaged(): Boolean = applies(config())

    /**
     * `true` when the raw positioning sources (WiFi scans, cell information,
     * NMEA / GNSS measurements) must be denied to this process.
     *
     * Deliberately *not* tied to `antiMock`: hiding mock traces and preventing an
     * app from computing a real fix are different jobs, and the second one is what
     * stops a map app from drifting off the injected position a few seconds in.
     * What it is tied to is [applies], so only a process that is being spoofed
     * loses those inputs — the rest of the device keeps working.
     */
    fun sourceLockEngaged(): Boolean {
        val cfg = config()
        return cfg.strictSources && applies(cfg)
    }

    /**
     * `true` when the map/location SDK channels should take over this process.
     *
     * Separate from [sourceLockEngaged] because the two attack opposite ends of
     * the same problem and are useful independently: the source lock starves an
     * SDK's engine of input, while this one commandeers what the SDK publishes.
     * An app that still worked before [strictSources] existed only needs the
     * second; one whose SDK caches a fix needs the first.
     */
    fun mapSdkEngaged(): Boolean {
        val cfg = config()
        return cfg.mapSdkCompat && applies(cfg)
    }

    /** Package this module instance is attached to, as reported by the framework. */
    fun packageName(): String? = processPackage

    // ------------------------------------------------------------- position

    /**
     * The coordinate to report: the anchor, or a point wandering around it when
     * drift is enabled.
     */
    fun position(): Pair<Double, Double> {
        val cfg = config()
        if (cfg.joystickSpeedMps > 0f || !cfg.driftEnabled || cfg.driftRadiusMeters <= 0.0) {
            driftEngine.reset()
            return cfg.latitude to cfg.longitude
        }
        return driftEngine.position(cfg, SystemClock.elapsedRealtime())
    }

    /** Motion convention: 0=east/+90=north; Android bearing: 0=north/90=east. */
    fun motionEngaged(): Boolean = active() &&
        (current.joystickSpeedMps > 0f || SystemClock.elapsedRealtime() - lastMotionMs < 2_000L)
    fun motionSpeed(): Float = config().let {
        if (it.joystickSpeedMps <= 0f) 0f
        else (it.motionSpeedMps ?: (it.joystickSpeedMps * it.joystickSensitivity)).coerceIn(0f, it.maxSpeedMps)
    }
    fun motionBearing(): Float = (90f - config().joystickHeadingDeg + 360f) % 360f

    /** Latitude only — the hot path for `Location.getLatitude()`. */
    fun latitude(): Double = position().first

    /** Longitude only. */
    fun longitude(): Double = position().second

    /**
     * The anchor expressed in the datum a given SDK channel speaks.
     *
     * Converted on every call rather than cached: drift moves the point between
     * calls, so a cached conversion would pin a drifting track to a fixed
     * offset — the one thing that would give the drift away.
     */
    fun positionIn(datum: Datum): Pair<Double, Double> {
        if (datum == Datum.WGS84) return position()
        val (lat, lng) = position()
        return Coords.fromWgs84(lat, lng, datum)
    }

    // ------------------------------------------------------------- location

    /** Location providers the platform actually defines. */
    private val KNOWN_PROVIDERS = setOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        LocationManager.FUSED_PROVIDER,
    )

    /**
     * Builds the fake [Location] handed back to the hooked app.
     *
     * When [original] is supplied and *recent*, its timing metadata is carried
     * over, because apps routinely reject or discard a fix whose `time` /
     * `elapsedRealtimeNanos` look stale or impossible. The provider string is
     * likewise preserved when it is a real one — an app that asked the `gps`
     * provider and got a `fused` fix back will usually notice.
     *
     * ## Why a stale original is *not* carried over
     *
     * `getLastKnownLocation` can return a fix from hours ago, and carrying that
     * timestamp onto the fake position hands the app a coordinate that says "the
     * device was here this morning". A check-in flow reads exactly that as "no
     * usable fix yet" and keeps waiting, or falls back to its own engine — the
     * symptom being a fake point that never becomes selectable, however correct
     * the coordinate is. Re-dating a stale fix is also what the reference
     * implementation behind the 企业微信 check-in path does: it builds a fresh
     * `Location`, stamps `time` and `elapsedRealtimeNanos` from the clock, and
     * never copies either from what the platform had.
     *
     * The bearing (and its accuracy) is still carried over; it is direction, not
     * freshness, and a fabricated one would be noise.
     */
    fun buildLocation(original: Location?, provider: String? = null): Location {
        val resolvedProvider = when {
            provider != null && provider in KNOWN_PROVIDERS -> provider
            original != null && original.provider in KNOWN_PROVIDERS -> original.provider
            else -> LocationManager.GPS_PROVIDER
        }

        // Preserve flags/extras/altitude and GPS quality carried by a real fix.
        // Empty Location() every tick was interpreted as satellite/quality loss.
        val fake = if (original != null) Location(original) else Location(resolvedProvider)
        fake.provider = resolvedProvider

        // `time` is wall-clock milliseconds and 0 means "never set"; subtracting
        // that from now would otherwise read as a fix from 1970 and qualify as
        // fresh on the wrong side of the comparison.
        val originalTime = original?.time ?: 0L
        val originalIsFresh = originalTime > 0L &&
            System.currentTimeMillis() - originalTime in 0..FIX_FRESH_WINDOW_MS

        // Copy constructor also preserves hasBearing/hasAltitude instead of fabricating flags.
        if (originalIsFresh) {
            fake.time = originalTime
            // A `Location` that carried a `time` but no monotonic clock is
            // possible (a fix rebuilt by a vendor HAL), and copying the zero
            // would make the fake look older than the clock it is stamped with.
            val originalElapsed = original?.elapsedRealtimeNanos ?: 0L
            fake.elapsedRealtimeNanos = if (originalElapsed in 1..SystemClock.elapsedRealtimeNanos()) {
                originalElapsed
            } else {
                SystemClock.elapsedRealtimeNanos() - FIX_LAG_NANOS
            }
        } else {
            stampTiming(fake)
        }

        applyTo(fake)
        synchronized(timestampLock) {
            val stamp = fake.elapsedRealtimeNanos
            if (stamp in 1..SystemClock.elapsedRealtimeNanos()) lastOutputNs = maxOf(lastOutputNs, stamp)
        }
        return fake
    }

    /**
     * Dates [location] the way a real chipset would.
     *
     * Backdated slightly: a fix dated "now" but never refreshed is itself a mild
     * tell, and a small lag is what real hardware reports.
     *
     * Shared with the map-SDK channels — they build their payloads from scratch
     * through a one-argument constructor, so without this every synthetic fix
     * would carry whatever timestamp the object was born with.
     */
    fun stampTiming(location: Location) {
        // Wall and monotonic clocks describe the SAME output instant. A backdated
        // synthetic fix after a newer physical callback used to move time backwards.
        synchronized(timestampLock) {
            val nowNs = SystemClock.elapsedRealtimeNanos()
            val candidate = (nowNs - FIX_LAG_NANOS).coerceAtLeast(1L)
            val stamp = maxOf(candidate, lastOutputNs + 1L).coerceAtMost(nowNs)
            lastOutputNs = stamp
            location.elapsedRealtimeNanos = stamp
            location.time = System.currentTimeMillis() - ((nowNs - stamp) / 1_000_000L)
        }
    }

    /**
     * Overwrites the coordinates carried by [location] *in place*.
     *
     * Preferred over [buildLocation] whenever the object is already owned by
     * someone else — a framework service handing a `LocationResult` to a batch of
     * listeners, for instance. Mutating in place keeps object identity and every
     * metadata field intact, which avoids breaking callers that key off either.
     *
     * [datum] exists for the map-SDK channels, whose payloads report GCJ-02 or
     * BD-09 rather than the platform's WGS-84. The write goes through the
     * *virtual* setter, which matters: `AMapLocation` keeps its own fields and
     * overrides `setLatitude`/`setAltitude`/`setSpeed` precisely so that writing
     * through a `Location` reference lands where its own getters read from.
     */
    fun applyTo(location: Location, datum: Datum = Datum.WGS84) {
        val cfg = config()
        val (lat, lng) = positionIn(datum)

        location.latitude = lat
        location.longitude = lng

        if (cfg.useAccuracy) {
            location.accuracy = cfg.accuracy
            // A fix that carries a horizontal accuracy but no vertical one is a
            // quiet tell on API 26+ — real hardware reports both, and "claims to
            // be a GPS fix but has only half its accuracy fields" is exactly the
            // shape a synthetic fix falls into. Derived from the horizontal value
            // rather than configured separately: the ratio between the two is
            // roughly constant on real hardware, so a second field in the UI
            // would only give the user another way to make the fix look wrong.
            location.verticalAccuracyMeters = cfg.accuracy * VERTICAL_ACCURACY_RATIO
        }
        if (cfg.useAltitude) {
            location.altitude = cfg.altitude
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                location.mslAltitudeMeters = cfg.altitude
                location.mslAltitudeAccuracyMeters = MSL_ALTITUDE_ACCURACY
            }
        }
        if (motionEngaged()) {
            location.speed = motionSpeed()
            location.speedAccuracyMetersPerSecond = SPEED_ACCURACY
            location.bearing = motionBearing()
            location.bearingAccuracyDegrees = 5f
            stampTiming(location)
        } else if (cfg.useSpeed) {
            location.speed = cfg.speed
            location.speedAccuracyMetersPerSecond = SPEED_ACCURACY
        }

        if (cfg.antiMock) {
            MockTrace.scrub(location)
        }
    }

    /** Returns [candidate] when it is already a plausible provider name. */
    fun normaliseProvider(candidate: String?): String =
        when (candidate) {
            null -> LocationManager.GPS_PROVIDER
            in KNOWN_PROVIDERS -> candidate
            else -> LocationManager.GPS_PROVIDER
        }

    /** Accuracy attached to the synthetic altitude/speed values, in their units. */
    private const val SPEED_ACCURACY = 0.6f

    /** Vertical accuracy, as a multiple of the horizontal one. */
    private const val VERTICAL_ACCURACY_RATIO = 1.6f
    private const val MSL_ALTITUDE_ACCURACY = 3.0f

    /** How far behind "now" a synthetic fix is dated. */
    private const val FIX_LAG_MS = 250L
    private const val FIX_LAG_NANOS = 250_000_000L

    /**
     * How old the platform's own fix may be before its timestamp is not carried
     * over to the fake one.
     *
     * Five seconds is the interval a location provider is allowed to answer
     * `getLastKnownLocation` from its cache without re-reading the hardware, so a
     * fix younger than that is genuinely "the device is here right now" and its
     * timing is worth preserving exactly. Anything older starts to mean "the
     * device was there at some point", which is a different claim — see
     * [buildLocation].
     */
    private const val FIX_FRESH_WINDOW_MS = 5_000L
}
