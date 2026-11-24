package io.github.guanhui999.fakelocgum.xposed.hooks

import android.location.Location
import android.os.Handler
import android.os.Looper
import java.lang.ref.WeakReference
import io.github.guanhui999.fakelocgum.core.Datum
import io.github.guanhui999.fakelocgum.xposed.CapturedListener
import io.github.guanhui999.fakelocgum.xposed.LocationSamples
import io.github.guanhui999.fakelocgum.xposed.SdkLocationTemplate
import io.github.guanhui999.fakelocgum.xposed.Diag
import io.github.guanhui999.fakelocgum.xposed.HookKit
import io.github.guanhui999.fakelocgum.xposed.HookState
import io.github.guanhui999.fakelocgum.xposed.SdkDispatch
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hooks the *inside* of the map and location SDKs themselves.
 *
 * ## Why hooking `android.location` is not enough
 *
 * The rest of the module intercepts what the platform hands to the app. A
 * location SDK does not have to go through that path at all. Amap's, Baidu's and
 * Tencent's SDKs each ship a closed-source engine that mixes GNSS, WiFi and cell
 * data on their own hardware, and each publishes the result through its *own*
 * classes:
 *
 * ```
 * AmapLocationClient ──> AMapLocationListener.onLocationChanged(AMapLocation)
 * LocationClient     ──> BDLocationListener.onReceiveLocation(BDLocation)
 * TencentLocationManager ──> TencentLocationListener.onLocationChanged(TencentLocation, …)
 * ```
 *
 * A `BDLocation` is not an `android.location.Location` at all, and a
 * `TencentLocation` never passes through `LocationManager`. An app built on
 * these sees nothing this module does — which is exactly the "map opens on the
 * fake position and then settles onto the real one" report, except that with an
 * SDK that never consults the platform it never even starts on the fake position.
 *
 * ## One vendor, more than one package
 *
 * A vendor's Java package is not a constant: Tencent ships the same engine as
 * `com.tencent.map.geolocation` and, repackaged, as
 * `com.tencent.map.geolocation.sapp`. Which one an app calls was decided when it
 * was integrated, and the two are not interchangeable — a hook resolves classes
 * in *its* process, so naming the wrong one installs nothing and says nothing.
 * Every Tencent entry point below therefore goes through [TENCENT_FLAVORS]; see
 * [TencentFlavor] for the case that established it (企业微信's check-in page).
 *
 * ## The three channels do not work the same way
 *
 * | SDK | Payload | How it is rewritten |
 * |---|---|---|
 * | Amap | `AMapLocation **extends** android.location.Location` | written in place through the inherited setters, and the getters are read through them too |
 * | Baidu | `BDLocation`, a standalone class | written in place through its own public setters |
 * | Tencent | `TencentLocation`, a bare **interface** (a class in `sapp`) | the payload cannot be rebuilt, so the concrete class's getters are hooked on first sight |
 *
 * The Amap case is the one that looks trivial and is not: `AMapLocation` declares
 * `getLatitude()` reading its **own** field `q`, not the inherited
 * `mLatitude`. Writing a `Location` there would leave the value the app actually
 * reads untouched. What makes the write work is that `AMapLocation` also
 * overrides `setLatitude`/`setLongitude`/`setAltitude`/`setSpeed`/`setBearing`,
 * so a virtual call through the `Location` reference reaches those overrides and
 * lands in the right fields. `getAccuracy` is the one that delegates to
 * `super`, which is why it is on the list as well rather than assumed to follow.
 *
 * ## Datum
 *
 * Each channel is handed the coordinate in the datum its own SDK would have
 * reported: GCJ-02 for Amap and Tencent, BD-09 for Baidu. Feeding an SDK the
 * WGS-84 value it never produces would put the result several hundred metres
 * from the point the user picked, which reads as "it works but it is wrong" and
 * is far harder to diagnose than a plain failure.
 *
 * ## When the SDK is not there
 *
 * Every channel is looked up by name and skipped silently when absent. A module
 * that refuses to install because one app does not use Amap would be useless; a
 * module that logs a warning for it would bury the warnings that matter.
 */
internal object MapSdkHooks {

    private const val TAG = "MapSdk"
    private val amapListeners = Collections.synchronizedSet(HashSet<Class<*>>())
    private val amapPushers = Collections.synchronizedMap(WeakHashMap<Any, AmapPusher>())
    private val amapClientListeners = Collections.synchronizedMap(WeakHashMap<Any, WeakReference<Any>>())
    private val baiduListeners = Collections.synchronizedSet(HashSet<Class<*>>())
    private val baiduPushers = Collections.synchronizedMap(WeakHashMap<Any, BaiduPusher>())
    // Per-instance registrations are distinct from per-class hook installation.
    private val tencentPushers = Collections.synchronizedMap(WeakHashMap<Any, TencentPusher>())
    private val tencentReplaying = ThreadLocal<Boolean>()

    /**
     * One entry per class loader each channel was actually armed in.
     *
     * Per channel rather than one set for all three, because "armed" is a fact
     * about a single SDK: an app that only ships Baidu must not have its Baidu
     * work remembered as "this loader is done" and thereby block an Amap arm
     * later, and a channel that resolved nothing must stay retryable. A single
     * set filled on *entry* rather than on success — the arrangement until now —
     * made the first attempt, the one that runs before a hardened app has
     * decrypted its dex, permanently record the loader as armed while nothing
     * was installed at all.
     *
     * Weak keys on purpose: a `DexClassLoader` the app builds for one screen is
     * garbage once that screen is gone, and holding it strongly would pin the
     * whole loader — its dex, its `Class` objects — for the process's lifetime.
     */
    private val amapArmed: MutableSet<ClassLoader> = newLoaderSet()
    private val baiduArmed: MutableSet<ClassLoader> = newLoaderSet()
    private val tencentArmed: MutableSet<ClassLoader> = newLoaderSet()

    private fun newLoaderSet(): MutableSet<ClassLoader> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>()))

    /**
     * Loaders already told that a given SDK is not reachable from them.
     *
     * Without this the miss would be logged on every retry, and the retries are
     * deliberately frequent during the first minute of the process. The message
     * itself is the one that was missing when a hardened app was investigated:
     * "channel armed" was simply absent, and nothing said whether the SDK was
     * unreachable or the channels were blocked by a setting.
     */
    private val missReported: MutableMap<ClassLoader, MutableSet<String>> =
        Collections.synchronizedMap(WeakHashMap<ClassLoader, MutableSet<String>>())

    /**
     * Answers `false` and explains the miss once per loader and SDK.
     *
     * Written as an expression so the class lookups above can read
     * `?: return reportMissing(...)` — the channel's installer then has exactly
     * one exit that is easy to audit.
     */
    private fun reportMissing(loader: ClassLoader, sdk: String, detail: String): Boolean {
        val seen = synchronized(missReported) { missReported.getOrPut(loader) { HashSet() } }
        val first = synchronized(seen) { seen.add(sdk) }
        if (first) {
            HookKit.info(TAG, "$sdk SDK not reachable from ${loader.javaClass.name} yet ($detail) — will retry")
        }
        return false
    }

    /** Concrete `TencentLocation` implementations already hooked, one pass each. */
    private val tencentPayloads: MutableSet<Class<*>> =
        Collections.synchronizedSet(HashSet<Class<*>>())

    /** Address fields whose first read has already been reported, `sdk.name` keys. */
    private val addressSeen: MutableSet<String> = Collections.synchronizedSet(HashSet<String>())

    /**
     * Reports one address field the app just read, and answers it with the
     * anchor's own address.
     *
     * ## Coordinates and addresses are two separate lies
     *
     * Rewriting `getLatitude()` rewrites a number. The city, province, street and
     * POI the same payload carries are **strings the SDK computed from the real
     * fix** — by asking its own server, not by consulting anything on the device —
     * and no part of the coordinate path touches them. An app that maps the
     * coordinates it is handed, but prints the SDK's own `getCity()`, therefore
     * ends up half converted: a fake map with a real city in the header. From the
     * outside that reads as "part of the app follows the fake position and part of
     * it does not", which is the report this was written for.
     *
     * ## Two jobs in one hook
     *
     *  * the **first** read of each field is logged with the value the SDK
     *    actually produced. That line settles the one question nothing else can:
     *    an app that never asks the SDK for an address gets its city from
     *    somewhere this module cannot reach, while one that does read it can be
     *    fixed here;
     *  * with `SpoofConfig.syncAddress` on, the field answers the anchor's
     *    counterpart instead.
     *
     * ## Why it answers a value rather than nothing
     *
     * Answering an empty string was tried first and made things worse. CCB Life
     * reads this city name and looks the *name* up in its own table to get a code
     * — `position_city_code = lookup(cityName).code`, inside the WebView home page
     * — so an empty name skips the whole update, and the header keeps the city it
     * cached from the last real fix. It then never moves, for any anchor, which is
     * indistinguishable from the module doing nothing. A plausible wrong answer is
     * something the app can act on; no answer is not.
     *
     * Only `String` fields are ever replaced. Answering text to a getter that
     * normally returns a code would hand the app a value of the wrong type, and an
     * app that went on to parse it would be a crash this module caused.
     *
     * Reported once per field per process: an app reads `getCity()` far more often
     * than once, and the only question worth answering is whether it reads it at
     * all.
     */
    private fun observeAddress(sdk: String, name: String, original: Any?): Any? {
        // Same switch as the coordinate getters, not merely "spoofing is on": with
        // `mapSdkCompat` off nothing in this channel rewrites the payload, and a
        // substituted city there would pair the *real* coordinate with a fake name
        // — the half-converted state the channel exists to prevent, arrived at from
        // the other direction.
        if (!HookState.mapSdkEngaged()) return original
        val cfg = HookState.config()
        // `null` counts as much as a String here, and that is the crux of this
        // channel: the case worth rewriting is precisely the one where the SDK has
        // *no* address to offer. CCB Life's BDLocation answers every one of these
        // getters with null, so an `original is String` test skips exactly the
        // reads that need rewriting — the fake city gets resolved, stored, logged
        // and then handed straight past, and the app keeps the city it cached.
        //
        // A non-String, non-null value is still left alone: some of these getters
        // answer with an int or a long, and putting a String there is a crash we
        // would have caused ourselves.
        val answer = if (cfg.syncAddress && (original == null || original is String)) {
            cfg.addressFor(name)
        } else {
            null
        }
        if (addressSeen.add("$sdk.$name")) {
            HookKit.info(
                TAG,
                if (answer == null) {
                    "$sdk address field read: $name=\"$original\""
                } else {
                    // Both halves matter. Logging only the SDK's own value says
                    // nothing about whether the substitution happened — and in
                    // the case this channel exists for, that value is null, so
                    // the old one-liner read as "hooked, did nothing".
                    "$sdk address field read: $name=\"$original\" — answering \"$answer\""
                },
            )
        }
        return answer ?: original
    }

    /**
     * Installs [observeAddress] on every address getter in [names].
     *
     * Absent names are skipped silently, on purpose: the three SDKs disagree about
     * which parts they publish (`getTownship` only on Amap, `getAddrStr` only on
     * Baidu, `getVillage` only on Tencent), so a warning per missing name would
     * bury the ones that matter for an app that uses all three.
     *
     * The count is reported once at install time so that an empty log later reads
     * as "hooked, and the app never asked" rather than "the hooks never landed".
     * In the LSPosed log those two are otherwise identical, and they call for
     * opposite next steps.
     */
    private fun installAddressReads(clazz: Class<*>, sdk: String, names: Array<String>): Int {
        var hooked = 0
        for (name in names) {
            if (HookKit.methodsNamed(clazz, name).isEmpty()) continue
            hooked += HookKit.hookAll(clazz, name, TAG) { chain ->
                observeAddress(sdk, name, chain.proceed())
            }
        }
        if (hooked > 0) HookKit.info(TAG, "$sdk address fields hooked ($hooked getter(s))")
        return hooked
    }

    /**
     * Arms every channel whose SDK is reachable from [classLoader].
     *
     * Called repeatedly, on purpose: once for the app's own loader at
     * `onPackageReady`, again from [DynamicLoaderHooks] whenever a map class
     * loads through a loader we have not seen, and again from [LateArmHooks]
     * after the app finished creating and on a few timers after that. Each call
     * is cheap — three set lookups per channel already armed — because the only
     * work worth repeating is the lookup for a channel that has not resolved
     * yet.
     *
     * @return how many channels this call actually armed. Zero is the normal
     *   answer for an app that uses no map SDK at all, and also the answer for
     *   a hardened app whose dex is still encrypted; [LateArmHooks] exists to
     *   make the second case recover instead of staying silent.
     */
    fun install(classLoader: ClassLoader): Int {
        var armed = 0
        if (!amapArmed.contains(classLoader)) {
            val ok = runCatching { installAmap(classLoader) }
                .onFailure { HookKit.error(TAG, "AMap channel failed", it) }
                .getOrDefault(false)
            if (ok) {
                amapArmed.add(classLoader)
                armed++
            }
        }
        if (!baiduArmed.contains(classLoader)) {
            val ok = runCatching { installBaidu(classLoader) }
                .onFailure { HookKit.error(TAG, "Baidu channel failed", it) }
                .getOrDefault(false)
            if (ok) {
                baiduArmed.add(classLoader)
                armed++
            }
        }
        if (!tencentArmed.contains(classLoader)) {
            val ok = runCatching { installTencent(classLoader) }
                .onFailure { HookKit.error(TAG, "Tencent channel failed", it) }
                .getOrDefault(false)
            if (ok) {
                tencentArmed.add(classLoader)
                armed++
            }
        }
        return armed
    }

    // ==================================================================== Amap

    private const val AMAP_LOCATION = "com.amap.api.location.AMapLocation"
    private const val AMAP_LISTENER = "com.amap.api.location.AMapLocationListener"
    private const val AMAP_CLIENT = "com.amap.api.location.AMapLocationClient"

    /** Provider string the synthetic fix is tagged with. */
    private const val PROVIDER = "gps"

    /**
     * The reads `AMapLocation` answers from its own fields.
     *
     * Every one of them has a matching override on the same class, so writing
     * through a `Location` reference reaches it. `getAccuracy` is included
     * because it is the one that delegates to `Location.getAccuracy` instead —
     * verified in the SDK's bytecode, not assumed from the pattern.
     */
    private val AMAP_READS = arrayOf(
        "getLatitude",
        "getLongitude",
        "getAccuracy",
        "getAltitude",
        "getSpeed",
        "getBearing",
    )

    /**
     * The address parts `AMapLocation` publishes.
     *
     * Kept apart from [AMAP_READS] because the two answer different questions:
     * that list is what the fix *is*, this one is what the SDK made of it. None of
     * these values is derived from the coordinates on the device — the SDK asks
     * its own server — so rewriting the coordinate moves none of them.
     */
    private val AMAP_ADDRESS = arrayOf(
        "getCountry",
        "getProvince",
        "getCity",
        "getCityCode",
        "getDistrict",
        "getTownship",
        "getStreet",
        "getStreetNumber",
        "getAddress",
        "getPoiName",
        "getAoiName",
        "getRoad",
        "getAdCode",
        "getBuildingId",
        "getFloor",
        "getDescription",
        "getLocationDetail",
    )

    private fun installAmap(loader: ClassLoader): Boolean {
        val locationClass = HookKit.findClass(loader, AMAP_LOCATION)
            ?: return reportMissing(loader, "amap", "$AMAP_LOCATION not found")
        val listenerClass = HookKit.findClass(loader, AMAP_LISTENER)
            ?: return reportMissing(loader, "amap", "$AMAP_LISTENER not found")
        val clientClass = HookKit.findClass(loader, AMAP_CLIENT)
            ?: return reportMissing(loader, "amap", "$AMAP_CLIENT not found")

        var reads = 0
        for (name in AMAP_READS) {
            reads += HookKit.hookAll(locationClass, name, TAG) { chain ->
                if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
                (chain.thisObject as? Location)?.let { HookState.applyTo(it, Datum.GCJ02) }
                chain.proceed()
            }
        }

        installAddressReads(locationClass, "amap", AMAP_ADDRESS)

        var opened = 0
        opened += HookKit.hookAll(clientClass, "setLocationListener", TAG) { chain ->
            val forwarded = chain.proceed()
            for (argument in chain.args) {
                if (argument != null && listenerClass.isInstance(argument)) {
                    subscribeAmap(argument, listenerClass, locationClass)
                    chain.thisObject?.let { amapClientListeners[it] = WeakReference(argument) }
                }
            }
            forwarded
        }
        HookKit.hookAll(clientClass, "unRegisterLocationListener", TAG) { chain ->
            for (argument in chain.args) if (argument != null) {
                SdkDispatch.unregister(argument)
                amapPushers.remove(argument)
            }
            chain.proceed()
        }
        for (entry in arrayOf("stopLocation", "onDestroy")) {
            HookKit.hookAll(clientClass, entry, TAG) { chain ->
                chain.thisObject?.let { owner ->
                    amapClientListeners[owner]?.get()?.let(SdkDispatch::unregister)
                    if (entry == "onDestroy") amapClientListeners.remove(owner)
                }
                chain.proceed()
            }
        }
        HookKit.hookAll(clientClass, "startLocation", TAG) { chain ->
            val result = chain.proceed()
            chain.thisObject?.let { owner -> amapClientListeners[owner]?.get()?.let { listener ->
                amapPushers[listener]?.let { SdkDispatch.register(listener, it) }
            } }
            result
        }

        if (reads > 0 || opened > 0) {
            HookKit.info(TAG, "AMap channel armed ($reads read path(s), $opened listener entry point(s))")
        }
        return reads > 0 || opened > 0
    }

    /**
     * Wires one `AMapLocationListener`: its callback gets rewritten, and the
     * listener is enrolled for the active push.
     */
    private fun subscribeAmap(listener: Any, listenerClass: Class<*>, locationClass: Class<*>) {
        // Hook each callback class once, but keep a separate quality template per instance.
        if (amapListeners.add(listener.javaClass)) {
            HookKit.hookAll(listener.javaClass, "onLocationChanged", TAG) { chain ->
                val payload = chain.args.firstOrNull() as? Location
                val owner = chain.thisObject
                if (payload != null && owner != null && !LocationSamples.isSynthetic()) {
                    amapPushers[owner]?.remember(payload)
                }
                if (HookState.mapSdkEngaged() && payload != null) HookState.applyTo(payload, Datum.GCJ02)
                chain.proceed()
            }
        }
        val pusher = AmapPusher(listener, locationClass)
        amapPushers[listener] = pusher
        SdkDispatch.register(listener, pusher)
        Diag.event("amap", "AMap callback registered; waiting for real quality template rather than overwriting it with empty metadata")
    }

    private class AmapPusher(listener: Any, private val locationClass: Class<*>) : CapturedListener(listener) {
        private val template = SdkLocationTemplate(locationClass)
        private val callback = HookKit.findMethod(listener.javaClass, "onLocationChanged", locationClass)
        private val errorCode = HookKit.findMethod(locationClass, "getErrorCode")
        private var waitingReported = false

        fun remember(payload: Location) {
            template.capture(payload)
        }

        override fun deliver(target: Any) {
            if (!HookState.mapSdkEngaged() || !SdkDispatch.isRegistered(target, this)) return
            val method = callback ?: return
            val payload = template.snapshot() as? Location
            if (payload == null) {
                if (!waitingReported) {
                    waitingReported = true
                    HookKit.info("GpsHealth", "AMap synthetic tick waiting for original payload/copy API; original callbacks remain untouched")
                }
                return
            }
            // Real failures are not turned into claims of a valid satellite fix.
            val failure = runCatching { errorCode?.invoke(payload) as? Number }.getOrNull()?.toInt()
            if (failure != null && failure != 0) return
            HookState.stampTiming(payload)
            HookState.applyTo(payload, Datum.GCJ02)
            LocationSamples.deliver { method.invoke(target, payload) }
        }
    }

    // =================================================================== Baidu

    private const val BAIDU_LOCATION = "com.baidu.location.BDLocation"
    private const val BAIDU_LISTENER = "com.baidu.location.BDLocationListener"
    private const val BAIDU_ABSTRACT_LISTENER = "com.baidu.location.BDAbstractLocationListener"
    private const val BAIDU_CLIENT = "com.baidu.location.LocationClient"

    /**
     * Entry point that takes a listener.
     *
     * `LocationClient` has no second one — unlike Amap, which also offers a
     * one-shot query — so unlike the other two channels this list has a single
     * member. Kept as a list anyway so the three installers read the same way.
     */
    private val BAIDU_ENTRIES = arrayOf("registerLocationListener")

    /** `BDLocation.TypeGpsLocation` — a valid, high-quality fix. */
    private const val BD_TYPE_GPS = 61

    /**
     * `BDLocation.TypeNetWorkLocation` — the type Baidu returns for an
     * indoor/server-assisted fix, which is what a spoofed position has to look
     * like when the app is not assumed to be under open sky.
     *
     * Used only to *rescue* a callback that arrived as a failure: the SDK's own
     * result may be `167` (the server could not locate) or `505` (request
     * refused), and an app that branches on `getLocType()` will call that a
     * failure no matter how good the coordinates under it are.
     */
    private const val BD_TYPE_NETWORK = 161

    /**
     * Datum tag written into `BDLocation`.
     *
     * `gcj02`, not `bd09ll`, and that is a measurement rather than a
     * preference: CCB's employee app asks Baidu for `gcj02`
     * (`LocationClientOption.setCoorType("gcj02")`), and its check-in WebView
     * receives `coordType:"gcj02"` with GCJ-02 numbers in the real-position
     * case. Answering `bd09ll` makes the page convert a coordinate that is not
     * BD-09, which moves the point by the BD-09/GCJ-02 offset (~500 m) before
     * the distance check runs.
     *
     * The datum tag and the numbers under it always move together — see
     * [BaiduWriter.apply], which converts the anchor to the same datum.
     */
    private const val BD_COORD_TYPE = "gcj02"

    /**
     * Satellite count on a synthetic fix.
     *
     * A `TypeGpsLocation` fix with zero satellites is a contradiction a detector
     * can read straight off the object, so a synthesised one has to carry a
     * plausible number. Twelve is an ordinary open-sky count — high enough to
     * justify a GPS-grade fix, low enough not to look like an idealised maximum.
     */
    private const val BD_SATELLITES = 12

    /** `BDLocation.setTime` takes a formatted string, not a millisecond count. */
    private const val BD_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

    /**
     * The address parts `BDLocation` publishes.
     *
     * Same reasoning as [AMAP_ADDRESS]: these are strings the SDK's server
     * produced, not values derived from the coordinates, so they stay real no
     * matter what the coordinate hooks report.
     */
    private val BAIDU_ADDRESS = arrayOf(
        "getCountry",
        "getProvince",
        "getCity",
        "getCityCode",
        "getDistrict",
        "getTown",
        "getStreet",
        "getStreetNumber",
        "getAddrStr",
        "getLocationDescribe",
        "getAdCode",
    )

    private fun installBaidu(loader: ClassLoader): Boolean {
        val locationClass = HookKit.findClass(loader, BAIDU_LOCATION)
            ?: return reportMissing(loader, "baidu", "$BAIDU_LOCATION not found")
        val listenerClass = HookKit.findClass(loader, BAIDU_LISTENER)
            ?: return reportMissing(loader, "baidu", "$BAIDU_LISTENER not found")
        // `LocationClient.registerLocationListener` has two overloads — one for
        // the interface, one for `BDAbstractLocationListener`, the abstract base
        // most apps actually extend. Matching only the interface would silently
        // skip every registration made through the other one, which is the
        // majority of them.
        val abstractListener = HookKit.findClass(loader, BAIDU_ABSTRACT_LISTENER)
        val clientClass = HookKit.findClass(loader, BAIDU_CLIENT)
            ?: return reportMissing(loader, "baidu", "$BAIDU_CLIENT not found")
        val writer = BaiduWriter(locationClass)

        fun isListener(value: Any?): Boolean = value != null &&
            (listenerClass.isInstance(value) || abstractListener?.isInstance(value) == true)

        var reads = 0
        for (name in writer.readerNames) {
            reads += HookKit.hookAll(locationClass, name, TAG) { chain ->
                val payload = chain.thisObject
                if (HookState.mapSdkEngaged() && payload != null) writer.apply(payload)
                chain.proceed()
            }
        }

        installAddressReads(locationClass, "baidu", BAIDU_ADDRESS)

        // `getLocType` is deliberately absent from [BaiduWriter.readerNames]:
        // those hooks write coordinates, this one must not. But it has to be
        // hooked *somewhere*, because almost every app branches on the type
        // before it reads a single number:
        //
        //     if (loc.getLocType() != 161) { showFailure(); return; }
        //
        // On that path the rescue in [BaiduWriter.apply] never runs — the app
        // never asks for a coordinate, so `apply` is never triggered, and the
        // callback's original 167 / 505 reaches the branch untouched. That is a
        // screen that says "位置获取失败" while the object underneath carries
        // perfectly good spoofed coordinates. Answering the getter closes it.
        HookKit.hookAll(locationClass, "getLocType", TAG) { chain ->
            val reported = chain.proceed()
            if (HookState.mapSdkEngaged() && reported is Int && !succeeded(reported)) {
                Diag.event(
                    "locType:$reported",
                    "BDLocation.getLocType() reported $reported — answered $BD_TYPE_NETWORK so the app does not branch on a failed type",
                )
                BD_TYPE_NETWORK
            } else {
                reported
            }
        }

        var opened = 0
        for (entry in BAIDU_ENTRIES) {
            opened += HookKit.hookAll(clientClass, entry, TAG) { chain ->
                val forwarded = chain.proceed()
                for (argument in chain.args) {
                    if (isListener(argument)) subscribeBaidu(argument!!, listenerClass, locationClass, writer)
                }
                forwarded
            }
        }
        HookKit.hookAll(clientClass, "unRegisterLocationListener", TAG) { chain ->
            for (argument in chain.args) if (argument != null) SdkDispatch.unregister(argument)
            chain.proceed()
        }

        if (reads > 0 || opened > 0) {
            HookKit.info(TAG, "Baidu channel armed ($reads read path(s), $opened listener entry point(s))")
        }
        return reads > 0 || opened > 0
    }

    /**
     * Baidu's success codes: 61–66 are the various GPS/offline results, 68 and
     * 161 the network ones. 67 (offline lookup failed), 162 (no permission),
     * 167 (server could not locate) and the 5xx family are failures.
     *
     * A `null` type counts as a failure: the payload then cannot prove it is a
     * fix, and answering with a good one costs nothing.
     */
    private fun succeeded(type: Int?): Boolean =
        type != null && (type in 61..66 || type == 68 || type == BD_TYPE_NETWORK)

    /**
     * `BDLocation` shares nothing with `android.location.Location`, so there is
     * no base class to write through: every value goes in through the SDK's own
     * setter. The `Method` handles are resolved once, at install time, and reused
     * on every read — reflection lookup on `getLatitude()` would be far too slow
     * for a path an app may hit hundreds of times a second.
     */
    private class BaiduWriter(locationClass: Class<*>) {

        private val latitude = HookKit.findMethod(locationClass, "setLatitude", java.lang.Double.TYPE)
        private val longitude = HookKit.findMethod(locationClass, "setLongitude", java.lang.Double.TYPE)
        private val altitude = HookKit.findMethod(locationClass, "setAltitude", java.lang.Double.TYPE)
        private val speed = HookKit.findMethod(locationClass, "setSpeed", java.lang.Float.TYPE)
        private val radius = HookKit.findMethod(locationClass, "setRadius", java.lang.Float.TYPE)
        private val locType = HookKit.findMethod(locationClass, "setLocType", java.lang.Integer.TYPE)

        /**
         * Reads back what the SDK decided before [apply] touches the object.
         *
         * Without it there is no way to tell a working callback from a failed
         * one: a failed fix carries perfectly good coordinates *fields*, it is
         * `locType` alone that says "this is not a position". The push path
         * never needs it (it builds the object itself); the callback path does,
         * on every delivery.
         */
        private val locTypeGetter = HookKit.findMethod(locationClass, "getLocType")
        private val coorType = HookKit.findMethod(locationClass, "setCoorType", String::class.java)
        private val satellites = HookKit.findMethod(locationClass, "setSatelliteNumber", java.lang.Integer.TYPE)
        private val time = HookKit.findMethod(locationClass, "setTime", String::class.java)

        /**
         * Getter names that trigger a rewrite when they are read.
         *
         * `getLocType` is *not* on this list: a real fix already carries a
         * truthful type and this module has nothing better to put there, so
         * hooking it would only add a call for no change.
         */
        val readerNames = arrayOf(
            "getLatitude",
            "getLongitude",
            "getRadius",
            "getAltitude",
            "getSpeed",
            "getCoorType",
        )

        /**
         * @param synthesised `true` only for a payload this module built itself.
         *   A fix the SDK produced already carries a truthful `locType` (and the
         *   satellite count behind it); overwriting that with a canned
         *   "GPS, 12 satellites" on every read would replace real provenance with
         *   a fixed story, which is both unnecessary and a tell.
         *
         *   What the SDK produced is *not* always a fix, though. Once the module
         *   has cut `getAllCellInfo` / `getScanResults` — which it does for every
         *   app in scope, see `SourceLockHooks` — Baidu has no radio input left to
         *   locate with, so its own callback arrives as `167` (server could not
         *   locate) or `505` (request refused). Those callbacks still carry the
         *   coordinates we put there, but an app that branches on `getLocType()`
         *   reads the type, not the numbers, and reports "location failed". So a
         *   failed type is raised to [BD_TYPE_NETWORK]: the payload now *is* a
         *   synthetic fix, and its type should say so.
         */
        fun apply(target: Any, synthesised: Boolean = false) {
            val cfg = HookState.config()
            val (lat, lng) = HookState.positionIn(Datum.GCJ02)

            call(latitude, target, lat)
            call(longitude, target, lng)
            call(coorType, target, BD_COORD_TYPE)
            if (cfg.useAccuracy) call(radius, target, cfg.accuracy)
            if (cfg.useAltitude) call(altitude, target, cfg.altitude)
            // Baidu setSpeed is km/h; Android/NMEA motion state is metres/second.
            if (HookState.motionEngaged()) call(speed, target, HookState.motionSpeed() * 3.6f)
            else if (cfg.useSpeed) call(speed, target, cfg.speed * 3.6f)

            if (synthesised) {
                // No real GPS sample exists: classify fallback as network, not GPS/12 satellites.
                call(locType, target, BD_TYPE_NETWORK)
                call(time, target, SimpleDateFormat(BD_TIME_PATTERN, Locale.US).format(Date()))
            } else if (!succeeded(locTypeOf(target))) {
                call(locType, target, BD_TYPE_NETWORK)
                call(time, target, SimpleDateFormat(BD_TIME_PATTERN, Locale.US).format(Date()))
            }
        }

        /**
         * The type the SDK reported, or `null` when the getter could not be
         * resolved on this build. Read *before* [apply] rewrites it.
         */
        fun locTypeOf(target: Any): Int? =
            runCatching { locTypeGetter?.invoke(target) as? Int }.getOrNull()

        private fun call(method: Method?, receiver: Any, value: Any) {
            if (method == null) return
            runCatching { method.invoke(receiver, value) }
        }
    }

    private fun subscribeBaidu(
        listener: Any,
        listenerClass: Class<*>,
        locationClass: Class<*>,
        writer: BaiduWriter,
    ) {
        if (baiduListeners.add(listener.javaClass)) {
            HookKit.hookAll(listener.javaClass, "onReceiveLocation", TAG) { chain ->
                val payload = chain.args.getOrNull(0)
                val owner = chain.thisObject
                if (owner != null && payload != null && !LocationSamples.isSynthetic()) {
                    baiduPushers[owner]?.remember(payload)
                }
                if (HookState.mapSdkEngaged() && payload != null) {
                    val reported = writer.locTypeOf(payload)
                    writer.apply(payload)
                    if (owner != null) recordCallback(owner, reported)
                }
                chain.proceed()
            }
        }

        val factory = BaiduFactory.of(locationClass)
        if (factory == null) {
            HookKit.warn(TAG, "BDLocation has no usable constructor — Baidu push path disabled")
            return
        }

        val pusher = BaiduPusher(listener, locationClass, factory, writer)
        baiduPushers[listener] = pusher
        SdkDispatch.register(listener, pusher)
        Diag.event("baidu", "Baidu listener captured; provider/quality metadata preserved from original callbacks")
        probe(listener)
    }

    /**
     * One breadcrumb per distinct `locType`, plus one for the listener class.
     *
     * Deduplicated by value rather than suppressed entirely, because the
     * interesting case is precisely a *change*: a 161 arriving normally and a 167
     * arriving after the radio inputs were cut are different diagnoses, and a
     * once-per-process line would show whichever came first. The class name says
     * which wrapper in the app is listening — the SDK's own `BDAbstractLocation-
     * Listener` subclasses all look alike from the outside, but the app's are
     * named after its own package, and that is what identifies the call path.
     */
    private fun recordCallback(listener: Any, reported: Int?) {
        val type = reported?.toString() ?: "unreadable"
        Diag.event(
            "baidu-cb:$type",
            "Baidu callback from ${listener.javaClass.name} arrived as locType=$type — answered with a fix on the anchor",
        )
    }

    /**
     * Number of catalogue entries left for [probe] before it stops hooking.
     *
     * A hard cap on purpose: this runs inside someone else's process, and a class
     * with a hundred methods would otherwise get a hundred extra interceptions
     * installed for a diagnostic that has already said everything it can.
     */
    private const val PROBE_LIMIT = 40

    /**
     * Namespaces that belong to a map SDK rather than to the app.
     *
     * A listener under one of these *is* the SDK, and the SDK's own behaviour is
     * already documented by the channel logs; probing it would only add noise.
     * Anything else is the app's own wrapper — the layer that decides what the
     * user finally sees.
     */
    private val SDK_NAMESPACES = arrayOf("com.baidu.", "com.amap.", "com.tencent.")

    /**
     * Dumps what an app-side listener class does with the fix it is given.
     *
     * The map SDKs are only half the story: an app that wraps one tends to keep
     * its own class between the SDK and the screen, and that class — not the SDK —
     * is what decides "did we get a position, or do we show 地理位置获取失败".
     * When the SDK reports a good fix (`locType=61`), every field we answer is
     * filled in, and the screen still says failure, the decision is in there.
     *
     * Rather than guess at a name, this lists the class's methods once and hooks
     * each of them to record what it returned. That is cheap (one hook per method,
     * one log line per method, no per-call allocation beyond the guard) and it
     * answers the only question that matters: which method produced the answer the
     * screen rejected, and what was in it.
     *
     * Only classes under the app's own web bridge are probed; nothing generic
     * (`toString`, `equals`) is reachable anyway — `declaredMethods` is the class's
     * own, not `Object`'s.
     */
    private fun probe(listener: Any) {
        val clazz = listener.javaClass
        if (SDK_NAMESPACES.any { clazz.name.startsWith(it) }) return
        if (!HookKit.firstTime("probe:${clazz.name}")) return

        val names = clazz.declaredMethods
            .filter { !it.isSynthetic && it.name != "onReceiveLocation" }
            .map { it.name }
            .distinct()
            .take(PROBE_LIMIT)
        Diag.event("probe-methods:${clazz.name}", "probe ${clazz.name} methods=$names")

        // A listener that is also the app's location command is the only place
        // the reply the page reads can be rewritten, and this is the one route
        // to that class that does not depend on `ClassLoader.loadClass`
        // catching it. Which of the methods builds the reply is not knowable
        // from here, so every one of them is offered to the repair and its
        // guard decides.
        val answersLocation = WebBridgeHooks.isLocationCommand(clazz)
        for (name in names) {
            HookKit.hookAll(clazz, name, TAG) { chain ->
                val result = chain.proceed()
                val repaired = if (answersLocation) WebBridgeHooks.repairReply(result) else null
                Diag.event(
                    "probe-call:${clazz.name}#$name",
                    "${clazz.simpleName}#$name returned ${describe(result)}",
                )
                repaired ?: result
            }
        }
    }

    /** Keeps a probed return value to one log line; objects report their type only. */
    private fun describe(value: Any?): String = when {
        value == null -> "null"
        value is String -> if (value.length > 400) value.take(400) + "…" else value
        value is CharSequence -> value.toString().take(400)
        else -> value.javaClass.name
    }

    /**
     * Builds a fresh, empty `BDLocation` for the push path.
     *
     * `BDLocation(String)` is a **JSON deserialising** constructor, not a
     * provider-name one — unlike `AMapLocation(String)`, which really does take a
     * provider. Measured on Baidu locSDK 9.7 inside CCB Life (2026-09-29): handing
     * it `"gps"` made `BDLocation.<init>` run `new JSONObject("gps")` and throw
     * `Value gps of type java.lang.String cannot be converted to JSONObject` once
     * a second, so no synthetic fix was ever delivered and the push path was
     * silently dead while still costing the target app an exception per tick.
     *
     * The no-argument constructor is the correct one. The JSON form is kept only
     * as a fallback and fed `"{}"`, which parses to an empty object whose every
     * field the writer then sets.
     */
    private class BaiduFactory(private val constructor: Constructor<*>, private val argument: Any?) {

        fun create(): Any? = runCatching {
            if (argument == null) constructor.newInstance() else constructor.newInstance(argument)
        }.getOrNull()

        companion object {
            fun of(locationClass: Class<*>): BaiduFactory? {
                runCatching { locationClass.getConstructor() }
                    .getOrNull()
                    ?.let { return BaiduFactory(it, null) }
                runCatching { locationClass.getConstructor(String::class.java) }
                    .getOrNull()
                    ?.let { return BaiduFactory(it, EMPTY_JSON) }
                return null
            }

            /** Parses to an empty object; every field is left to [BaiduWriter]. */
            private const val EMPTY_JSON = "{}"
        }
    }

    private class BaiduPusher(
        listener: Any,
        private val locationClass: Class<*>,
        private val factory: BaiduFactory,
        private val writer: BaiduWriter,
    ) : CapturedListener(listener) {
        private val callback = HookKit.findMethod(listener.javaClass, "onReceiveLocation", locationClass)
        private val template = SdkLocationTemplate(locationClass)
        private val timeSetter = HookKit.findMethod(locationClass, "setTime", String::class.java)
        private var waitingReported = false
        fun remember(payload: Any) { template.capture(payload) }

        override fun deliver(target: Any) {
            if (!HookState.mapSdkEngaged() || !SdkDispatch.isRegistered(target, this)) return
            val method = callback ?: return
            val payload = template.snapshot()
            if (payload == null) {
                if (!waitingReported) { waitingReported = true; HookKit.info("GpsHealth", "Baidu tick waiting for original quality template") }
                return
            }
            if (!succeeded(writer.locTypeOf(payload))) return
            writer.apply(payload)
            timeSetter?.invoke(payload, SimpleDateFormat(BD_TIME_PATTERN, Locale.US).format(Date()))
            LocationSamples.deliver { method.invoke(target, payload) }
        }
    }

    // ================================================================= Tencent

    /**
     * One packaging of Tencent's location SDK.
     *
     * The engine is the same in every build; only the Java package differs, and
     * which one an app ends up with is decided by how it was integrated, not by
     * the SDK version. Tencent publishes the `com.tencent.map.geolocation` build
     * for apps that follow its setup guide; some apps — 企业微信 among them —
     * embed the same classes repackaged under `com.tencent.map.geolocation.sapp`.
     *
     * Getting this wrong is silent in the worst possible way: a channel armed
     * against a package the process does not contain resolves no class, installs
     * no hook and logs no warning, so the app behaves exactly as if this module
     * were not installed. That was the state of the 企业微信 check-in path until
     * 1.9.0 — the module hooked the public package while the app called the
     * `sapp` one, so the map sat on the real position whatever was configured.
     * Measured on 2026-10-22 against 企微小打手 1.6.1, whose entire location
     * channel names the `sapp` pair and nothing else.
     */
    private class TencentFlavor(
        /** Log label, and the key the per-field diagnostics dedupe on. */
        val label: String,
        val manager: String,
        val listener: String,
        /** An interface in the public build, a class in `sapp` — either is fine. */
        val location: String,
    )

    private val TENCENT_FLAVORS = arrayOf(
        TencentFlavor(
            "tencent",
            "com.tencent.map.geolocation.TencentLocationManager",
            "com.tencent.map.geolocation.TencentLocationListener",
            "com.tencent.map.geolocation.TencentLocation",
        ),
        TencentFlavor(
            "tencent-sapp",
            "com.tencent.map.geolocation.sapp.TencentLocationManager",
            "com.tencent.map.geolocation.sapp.TencentLocationListener",
            "com.tencent.map.geolocation.sapp.TencentLocation",
        ),
    )

    /** Entry points that take a listener. */
    private val TENCENT_ENTRIES = arrayOf(
        "requestLocationUpdates",
        "requestSingleFreshLocation",
    )

    /** Manager methods that return a payload instead of delivering one. */
    private val TENCENT_PULL_ENTRIES = arrayOf("getLastKnownLocation")

    /**
     * The address parts a `TencentLocation` implementation publishes.
     *
     * Same reasoning as [AMAP_ADDRESS], with one twist: the list lands on whichever
     * class *implements* the interface, and that is only known the first time a
     * payload shows up — see [armTencentPayload].
     *
     * `getName` is on the list because on Tencent it is the **POI label**, not the
     * address: a check-in screen prints it as the place ("腾讯滨海大厦") with
     * `getAddress()` underneath. Leaving it out meant the coordinate and the street
     * moved while the place name stayed real, which is precisely the half-converted
     * state this channel exists to prevent. It answers
     * [io.github.guanhui999.fakelocgum.core.SpoofConfig.addrPlaceName], never the
     * formatted address — see `addressFor`.
     */
    private val TENCENT_ADDRESS = arrayOf(
        "getNation",
        "getProvince",
        "getCity",
        "getCityCode",
        "getDistrict",
        "getTown",
        "getVillage",
        "getStreet",
        "getStreetNo",
        "getName",
        "getAddress",
    )

    /**
     * What a POI row publishes.
     *
     * The SDK's picker list is a `List<TencentPoi>` hanging off the payload, and
     * each element is a place with its own name, address and coordinate. An app
     * that builds its "choose the place" sheet from that list therefore reads a
     * *second* copy of the real position — one the payload getters never touch —
     * and 企业微信's check-in sheet is built exactly that way. Hooking the rows is
     * what makes the list agree with the map instead of contradicting it.
     */
    private val TENCENT_POI_ADDRESS = arrayOf("getName", "getAddress")

    /** Concrete payload classes already hooked, one pass each. */
    private val tencentPois: MutableSet<Class<*>> =
        Collections.synchronizedSet(HashSet<Class<*>>())

    /** Listener classes whose `onLocationChanged` is already intercepted. */
    private val tencentListeners: MutableSet<Class<*>> =
        Collections.synchronizedSet(HashSet<Class<*>>())

    private fun installTencent(loader: ClassLoader): Boolean {
        var armed = 0
        for (flavor in TENCENT_FLAVORS) {
            val ok = runCatching { installTencentFlavor(loader, flavor) }
                .onFailure { HookKit.error(TAG, "${flavor.label} channel failed", it) }
                .getOrDefault(false)
            if (ok) armed++
        }
        if (armed == 0) {
            // One line for the vendor, not one per package: the flavours are
            // alternative packagings of the same engine, so "both missing" is a
            // single fact, and two warnings would read as two SDKs to look for.
            return reportMissing(
                loader,
                "tencent",
                "no known package resolved (${TENCENT_FLAVORS.joinToString { it.label }})",
            )
        }
        return true
    }

    private fun installTencentFlavor(loader: ClassLoader, flavor: TencentFlavor): Boolean {
        val managerClass = HookKit.findClass(loader, flavor.manager) ?: return false

        // Diagnostic only. The listener is recognised by its *shape* below, not by
        // this type: the `sapp` build repackages it exactly like the manager, and a
        // name that drifts one version later must not be able to switch the whole
        // subscription path off. When this lookup fails, the log says so and the
        // shape test still arms whatever the app actually passes.
        if (HookKit.findClass(loader, flavor.listener) == null) {
            HookKit.warn(TAG, "${flavor.label}: ${flavor.listener} not found — recognising listeners by shape")
        }

        var opened = 0
        for (entry in TENCENT_ENTRIES) {
            opened += HookKit.hookAll(managerClass, entry, TAG) { chain ->
                val forwarded = chain.proceed()
                for (argument in chain.args) {
                    argument?.let {
                        if (isTencentListener(it)) {
                            subscribeTencent(it, flavor)
                            if (entry == "requestLocationUpdates" && (forwarded !is Int || forwarded == 0)) {
                                val callback = HookKit.methodsNamed(it.javaClass, "onLocationChanged")
                                    .firstOrNull { m -> m.parameterTypes.size == 3 }?.apply { isAccessible = true }
                                if (callback != null) {
                                    val looper = chain.args.firstOrNull { arg -> arg is Looper } as? Looper
                                    val pusher = TencentPusher(it, callback, looper)
                                    tencentPushers[it] = pusher
                                    SdkDispatch.register(it, pusher)
                                    // Seed from a real concrete SDK instance if the first callback was synchronous.
                                    val cached = HookKit.findMethod(managerClass, "getLastKnownLocation")
                                        ?.let { getter -> runCatching { getter.invoke(chain.thisObject) }.getOrNull() }
                                    if (cached != null) {
                                        armTencentPayload(cached, flavor)
                                        pusher.remember(arrayOf(cached, 0, ""))
                                    }
                                }
                            }
                        }
                    }
                }
                forwarded
            }
        }

        // The one-shot queries hand back a payload without ever calling a listener.
        //
        // Armed whatever the switches say: installing the getter hooks is free and
        // they gate themselves per read, while *skipping* the install leaves an
        // object the app has already cached reading the real position for as long
        // as it lives — the case that motivated the declared-type hook below.
        for (entry in TENCENT_PULL_ENTRIES) {
            opened += HookKit.hookAll(managerClass, entry, TAG) { chain ->
                val forwarded = chain.proceed()
                armTencentPayload(forwarded, flavor)
                forwarded
            }
        }
        HookKit.hookAll(managerClass, "removeUpdates", TAG) { chain ->
            for (argument in chain.args) if (argument != null) {
                SdkDispatch.unregister(argument)
                tencentPushers.remove(argument)
            }
            chain.proceed()
        }

        // The declared payload type, hooked before any fix arrives.
        //
        // The lazy path can only arm a class it has *seen*, which is enough when
        // the app asks for a fix after this module was loaded — and not enough when
        // the SDK hands back an object it built or cached earlier. When the type is
        // a concrete class (the `sapp` build's shape) there is something to hook by
        // name right now; when it is the interface the public build publishes — or
        // an abstract base with no getter bodies — there is not, and the lazy path
        // is the only option.
        val locationClass = HookKit.findClass(loader, flavor.location)
        if (locationClass != null &&
            !locationClass.isInterface &&
            !Modifier.isAbstract(locationClass.modifiers)
        ) {
            armTencentPayloadClass(locationClass, flavor)
        }

        if (opened > 0) {
            HookKit.info(TAG, "${flavor.label} channel armed ($opened entry point(s))")
        }
        return opened > 0
    }

    /**
     * Whether [value] is something the SDK can deliver a fix to.
     *
     * A `TencentLocationListener` is an interface with a single interesting
     * method — `onLocationChanged(TencentLocation, int, String)` — and every
     * subscription call passes an implementation of it beside a
     * `TencentLocationRequest`. Testing the method shape rather than the type is
     * what keeps this working when the build repackages or renames the interface:
     * the hook needs a method it can intercept, not a name it can compare.
     *
     * `TencentLocationRequest` carries no `onLocationChanged`, so the more
     * numerous argument is rejected without a type lookup.
     */
    private fun isTencentListener(value: Any): Boolean =
        HookKit.methodsNamed(value.javaClass, "onLocationChanged").isNotEmpty()

    private fun subscribeTencent(listener: Any, flavor: TencentFlavor) {
        // One pass per listener *class*, not per registration.
        //
        // A check-in screen re-subscribes on every refresh, and the app typically
        // builds its listener once and reuses it — so without this guard the same
        // `Method` would be wrapped again every second, and a hook chain that grows
        // without bound is a stack overflow in someone else's process. Hooking the
        // class covers every instance of it, which is what the registration call
        // was going to give us anyway.
        if (!tencentListeners.add(listener.javaClass)) return

        val hooked = HookKit.hookAll(listener.javaClass, "onLocationChanged", TAG) { chain ->
            // Unconditional for the same reason as the pull path: arming is cheap,
            // gating happens per getter read, and a payload that arrived while the
            // module was off must not keep answering with the real fix after it is
            // switched on.
            armTencentPayload(chain.args.getOrNull(0), flavor)
            val listener = chain.thisObject
            if (listener != null && tencentReplaying.get() != true && chain.args.getOrNull(1) == 0) {
                tencentPushers[listener]?.remember(chain.args.toTypedArray())
            }
            chain.proceed()
        }
        if (hooked > 0) {
            Diag.event(
                "${flavor.label}-listener",
                "${flavor.label} listener captured (onLocationChanged x$hooked) — the payload class is now hooked",
            )
        }
    }

    private class TencentPusher(
        listener: Any,
        private val callback: Method,
        looper: Looper?,
    ) : CapturedListener(listener) {
        private val handler = Handler(looper ?: Looper.getMainLooper())
        @Volatile private var sample: Array<Any?>? = null
        private val queued = AtomicBoolean(false)
        private var reported = false
        fun remember(args: Array<Any?>) { sample = args.copyOf() }
        override fun deliver(target: Any) {
            if (!HookState.mapSdkEngaged() || sample == null || !queued.compareAndSet(false, true)) return
            val recipient = WeakReference(target)
            if (!handler.post {
                try {
                    val listener = recipient.get()
                    val args = sample
                    if (listener != null && args != null && HookState.mapSdkEngaged() &&
                        SdkDispatch.isRegistered(listener, this)) {
                        tencentReplaying.set(true)
                        callback.invoke(listener, *args)
                        if (!reported) {
                            reported = true
                            HookKit.info("LivePush", "Tencent continuous callback refreshed ${callback.declaringClass.name}")
                        }
                    }
                } catch (error: Throwable) {
                    HookKit.error("LivePush", "Tencent callback refresh failed", error)
                } finally { tencentReplaying.set(false); queued.set(false) }
            }) queued.set(false)
        }
    }

    /**
     * Hooks the *concrete* class behind a `TencentLocation`.
     *
     * `TencentLocation` is an interface with no setters in the public build, and
     * its implementation lives outside the published SDK, so there is nothing to
     * write into and nothing to construct. Replacing the payload with a `Proxy`
     * would answer the getters, but the first app that casts the result back to
     * the real class — and some do — would take a `ClassCastException`. Hooking the
     * getters on whatever class arrives is the version that cannot crash the app:
     * it is installed the moment the payload is first seen, before the app's own
     * callback body reads it.
     *
     * Continuous registrations now retain one successful concrete payload for
     * callback refresh. No Proxy or guessed vendor constructor is introduced.
     *
     * ## Why every getter, not only the coordinate
     *
     * Coordinates and the strings describing them are two separate lies. The city,
     * province, street and POI name the payload carries were computed by the SDK's
     * **server** from the real fix, so rewriting `getLatitude()` moves none of
     * them. A check-in screen prints both: a map on the fake point with the real
     * place name in the chip, or — worse — a record whose coordinates and address
     * disagree. [TENCENT_ADDRESS] and [TENCENT_POI_ADDRESS] are what keep the two
     * halves consistent.
     */
    private fun armTencentPayload(payload: Any?, flavor: TencentFlavor) {
        val clazz = payload?.javaClass ?: return
        armTencentPayloadClass(clazz, flavor)
    }

    private fun armTencentPayloadClass(clazz: Class<*>, flavor: TencentFlavor) {
        if (!tencentPayloads.add(clazz)) return

        HookKit.hookAll(clazz, "getLatitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).first
        }
        HookKit.hookAll(clazz, "getLongitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).second
        }
        HookKit.hookAll(clazz, "getAccuracy", TAG) { chain ->
            val cfg = HookState.config()
            if (!HookState.mapSdkEngaged() || !cfg.useAccuracy) return@hookAll chain.proceed()
            cfg.accuracy
        }
        HookKit.hookAll(clazz, "getAltitude", TAG) { chain ->
            val cfg = HookState.config()
            if (!HookState.mapSdkEngaged() || !cfg.useAltitude) return@hookAll chain.proceed()
            cfg.altitude
        }
        HookKit.hookAll(clazz, "getSpeed", TAG) { chain ->
            val cfg = HookState.config()
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            if (HookState.motionEngaged()) HookState.motionSpeed()
            else if (cfg.useSpeed) cfg.speed else chain.proceed()
        }
        HookKit.hookAll(clazz, "getTime", TAG) { chain ->
            if (HookState.mapSdkEngaged()) System.currentTimeMillis() else chain.proceed()
        }
        HookKit.hookAll(clazz, "getBearing", TAG) { chain ->
            if (HookState.mapSdkEngaged() && HookState.motionEngaged()) HookState.motionBearing() else chain.proceed()
        }

        installAddressReads(clazz, flavor.label, TENCENT_ADDRESS)
        installTencentPoiReads(clazz, flavor)
    }

    /**
     * Arms the rows of `getPoiList()` on first sight.
     *
     * The list is read, not replaced: keeping the app's own `List` object and
     * every element's identity means a caller that caches the list, sorts it or
     * casts an element back to the SDK's POI class keeps working. What changes is
     * what each element answers — the anchor's coordinate, its place label and its
     * address — so the picker sheet and the map tell the same story.
     */
    private fun installTencentPoiReads(clazz: Class<*>, flavor: TencentFlavor) {
        HookKit.hookAll(clazz, "getPoiList", TAG) { chain ->
            val result = chain.proceed()
            // Armed regardless of the switches, like the payload itself: the row
            // getters gate per read, and a list handed out while the module was off
            // must not keep answering with the real places after it is switched on.
            val first = (result as? List<*>)?.firstOrNull() ?: return@hookAll result
            armTencentPoi(first.javaClass, flavor)
            result
        }
    }

    private fun armTencentPoi(clazz: Class<*>, flavor: TencentFlavor) {
        if (!tencentPois.add(clazz)) return

        installAddressReads(clazz, "${flavor.label}-poi", TENCENT_POI_ADDRESS)

        HookKit.hookAll(clazz, "getLatitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).first
        }
        HookKit.hookAll(clazz, "getLongitude", TAG) { chain ->
            if (!HookState.mapSdkEngaged()) return@hookAll chain.proceed()
            HookState.positionIn(Datum.GCJ02).second
        }
    }
}
