package io.github.guanhui999.fakelocgum.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Hand-rolled JSON codec for [SpoofConfig].
 *
 * `org.json` is used on purpose: it lives in the boot classpath, so the exact
 * same decoder runs inside a target app's process without dragging Gson (and
 * its reflection) into every process we hook. Decoding is defensive — a
 * malformed or truncated blob falls back to defaults rather than throwing
 * inside `Location.getLatitude()`.
 */
object ConfigCodec {

    private const val VERSION = 1

    private const val K_VERSION = "v"
    private const val K_PLAYING = "playing"
    private const val K_TARGET_PACKAGE = "target_package"
    private const val K_TARGET_PACKAGES = "target_packages"
    private const val K_BLOCK_RAW_GNSS = "block_raw_gnss"
    private const val K_OVERLAY = "overlay_enabled"
    private const val K_STRIDE_METERS = "stride_meters"
    private const val K_MAX_SPEED = "max_speed_mps"
    private const val K_ACCEL_SECONDS = "accel_seconds"
    private const val K_DECEL_SECONDS = "decel_seconds"
    private const val K_SENSOR_NOISE = "sensor_noise"
    private const val K_MANUAL_SENSOR = "manual_sensor"
    private const val K_MANUAL_STEP_FREQ = "manual_step_freq"
    private const val K_JOYSTICK_SENSITIVITY = "joystick_sensitivity"
    private const val K_JOYSTICK_SPEED = "joystick_speed_mps"
    private const val K_MOTION_SPEED = "motion_speed_mps"
    private const val K_JOYSTICK_HEADING = "joystick_heading_deg"
    private const val K_LAT = "lat"
    private const val K_LNG = "lng"
    private const val K_USE_ACCURACY = "use_accuracy"
    private const val K_ACCURACY = "accuracy"
    private const val K_USE_ALTITUDE = "use_altitude"
    private const val K_ALTITUDE = "altitude"
    private const val K_USE_SPEED = "use_speed"
    private const val K_SPEED = "speed"
    private const val K_DRIFT = "drift"
    private const val K_DRIFT_RADIUS = "drift_radius"
    private const val K_DRIFT_SPEED = "drift_speed"
    private const val K_ANTI_MOCK = "anti_mock"
    private const val K_STRICT_SOURCES = "strict_sources"
    private const val K_MAP_SDK = "map_sdk_compat"
    private const val K_SYNC_ADDRESS = "sync_address"
    private const val K_ADDR_COUNTRY = "addr_country"
    private const val K_ADDR_PROVINCE = "addr_province"
    private const val K_ADDR_CITY = "addr_city"
    private const val K_ADDR_DISTRICT = "addr_district"
    private const val K_ADDR_TOWN = "addr_town"
    // Added with [SpoofConfig.addrStreet]: the check-in reply carries the street
    // beside the formatted address, and it is a different getter. A blob from an
    // earlier build has no such key and decodes to blank, which the hook reads
    // as "leave getStreet() alone" — no migration needed.
    private const val K_ADDR_STREET = "addr_street"
    private const val K_ADDR_ADCODE = "addr_adcode"
    private const val K_ADDR_CITYCODE = "addr_citycode"
    // Added in 1.9.0 with [SpoofConfig.addrPlaceName]. A blob written before it
    // simply has no key and decodes to the empty string, which the address hooks
    // read as "leave the name getter alone" — the pre-1.9.0 behaviour, byte for
    // byte, so no migration is needed.
    private const val K_ADDR_PLACE = "addr_place"
    private const val K_ADDR_LINE = "addr_line"
    private const val K_MAP_PROVIDER = "map_provider"
    // Removed in 1.4.1 along with the built-in key: `map_credential` is no longer
    // written, and a blob that still carries one from an older build simply has
    // it ignored. Dropping the reader is safe because the field it fed fed only
    // the picker's own credential switch, never the hook side.
    private const val K_MAP_AK_BAIDU = "map_ak_baidu"
    private const val K_MAP_AK_AMAP = "map_ak_amap"
    private const val K_MAP_AK_TENCENT = "map_ak_tencent"
    private const val K_MAP_SK_TENCENT = "map_sk_tencent"
    // Added with [SpoofConfig.places]. Deliberately an additive key with no
    // version bump: a blob written before it decodes to an empty history (the
    // home screen falls back to the built-in cities), and a blob written after
    // it is ignored by any older build, which is the same forward-compatibility
    // every other field here already relies on.
    private const val K_PLACES = "places"
    private const val K_PINNED = "pinned"
    // Keep this fork's explicit single-package filter. Upstream's scope-only
    // schema must not silently widen a saved selection to every injected app.

    fun encode(config: SpoofConfig): String = JSONObject().apply {
        put("jog_profile", encodeJog(config.jogProfile))
        config.gaitFrame?.let { put("gait_frame", encodeGait(it)) }
        put(K_VERSION, VERSION)
        put(K_PLAYING, config.playing)
        put(K_TARGET_PACKAGE, config.targetPackage)
        // Legacy blobs round-trip unchanged; new multi-select blobs carry an authoritative array.
        config.targetPackages?.let { put(K_TARGET_PACKAGES, JSONArray(config.selectedPackages.sorted())) }
        put(K_BLOCK_RAW_GNSS, config.blockRawGnss)
        put(K_OVERLAY, config.overlayEnabled)
        put(K_STRIDE_METERS, config.strideMeters.toDouble())
        put(K_MAX_SPEED, config.maxSpeedMps.toDouble())
        put(K_ACCEL_SECONDS, config.accelerationSeconds.toDouble())
        put(K_DECEL_SECONDS, config.decelerationSeconds.toDouble())
        put(K_SENSOR_NOISE, config.sensorNoise.toDouble())
        put(K_MANUAL_SENSOR, config.manualSensorMode)
        put(K_MANUAL_STEP_FREQ, config.manualStepFrequencyHz.toDouble())
        put(K_JOYSTICK_SENSITIVITY, config.joystickSensitivity.toDouble())
        put(K_JOYSTICK_SPEED, config.joystickSpeedMps.toDouble())
        config.motionSpeedMps?.takeIf { it.isFinite() }?.let { put(K_MOTION_SPEED, it.toDouble()) }
        put(K_JOYSTICK_HEADING, config.joystickHeadingDeg.toDouble())
        put(K_LAT, config.latitude)
        put(K_LNG, config.longitude)
        put(K_USE_ACCURACY, config.useAccuracy)
        put(K_ACCURACY, config.accuracy.toDouble())
        put(K_USE_ALTITUDE, config.useAltitude)
        put(K_ALTITUDE, config.altitude)
        put(K_USE_SPEED, config.useSpeed)
        put(K_SPEED, config.speed.toDouble())
        put(K_DRIFT, config.driftEnabled)
        put(K_DRIFT_RADIUS, config.driftRadiusMeters)
        put(K_DRIFT_SPEED, config.driftSpeedMps.toDouble())
        put(K_ANTI_MOCK, config.antiMock)
        put(K_STRICT_SOURCES, config.strictSources)
        put(K_MAP_SDK, config.mapSdkCompat)
        put(K_SYNC_ADDRESS, config.syncAddress)
        put(K_ADDR_COUNTRY, config.addrCountry)
        put(K_ADDR_PROVINCE, config.addrProvince)
        put(K_ADDR_CITY, config.addrCity)
        put(K_ADDR_DISTRICT, config.addrDistrict)
        put(K_ADDR_TOWN, config.addrTown)
        put(K_ADDR_STREET, config.addrStreet)
        put(K_ADDR_ADCODE, config.addrAdCode)
        put(K_ADDR_CITYCODE, config.addrCityCode)
        put(K_ADDR_PLACE, config.addrPlaceName)
        put(K_ADDR_LINE, config.addrLine)
        put(K_MAP_PROVIDER, config.mapProviderId)
        put(K_MAP_AK_BAIDU, config.mapAkBaidu)
        put(K_MAP_AK_AMAP, config.mapAkAmap)
        put(K_MAP_AK_TENCENT, config.mapAkTencent)
        put(K_MAP_SK_TENCENT, config.mapSkTencent)
        put(K_PLACES, encodePlaces(config.places))
    }.toString()

    /**
     * Never throws. Any missing, wrong-typed or out-of-range field falls back to
     * the [SpoofConfig] default for that field, so a partially written blob (a
     * crash mid-write) still yields a usable config.
     *
     * A blob written by an older build simply has no key for a field added since,
     * and takes that field's default — which is why every new switch has to
     * default to "off, nothing changes", not to "on, it now behaves differently".
     */
    fun decode(raw: String?): SpoofConfig {
        if (raw.isNullOrBlank()) return SpoofConfig()
        val defaults = SpoofConfig()
        val json = try {
            JSONObject(raw)
        } catch (_: Throwable) {
            return defaults
        }

        return SpoofConfig(
            jogProfile = decodeJog(json.optJSONObject("jog_profile")),
            gaitFrame = decodeGait(json.optJSONObject("gait_frame")),
            playing = json.optBoolean(K_PLAYING, defaults.playing),
            targetPackage = json.optString(K_TARGET_PACKAGE, "").trim().take(255).let { if (it == "null") "" else it },
            targetPackages = if (json.has(K_TARGET_PACKAGES)) decodeTargets(json.optJSONArray(K_TARGET_PACKAGES)) else null,
            blockRawGnss = json.optBoolean(K_BLOCK_RAW_GNSS, false),
            overlayEnabled = json.optBoolean(K_OVERLAY, false),
            strideMeters = (json.optDouble(K_STRIDE_METERS, defaults.strideMeters.toDouble()).takeIf { it.isFinite() } ?: defaults.strideMeters.toDouble())
                .toFloat().coerceIn(0.1f, 3.0f),
            maxSpeedMps = (json.optDouble(K_MAX_SPEED, defaults.maxSpeedMps.toDouble()).takeIf { it.isFinite() } ?: defaults.maxSpeedMps.toDouble())
                .toFloat().coerceIn(0.0f, 4.5f),
            accelerationSeconds = (json.optDouble(K_ACCEL_SECONDS, defaults.accelerationSeconds.toDouble()).takeIf { it.isFinite() } ?: defaults.accelerationSeconds.toDouble())
                .toFloat().coerceIn(0.2f, 3.0f),
            decelerationSeconds = (json.optDouble(K_DECEL_SECONDS, defaults.decelerationSeconds.toDouble()).takeIf { it.isFinite() } ?: defaults.decelerationSeconds.toDouble())
                .toFloat().coerceIn(0.2f, 3.0f),
            sensorNoise = (json.optDouble(K_SENSOR_NOISE, defaults.sensorNoise.toDouble()).takeIf { it.isFinite() } ?: defaults.sensorNoise.toDouble())
                .toFloat().coerceIn(0.0f, 1.0f),
            manualSensorMode = json.optBoolean(K_MANUAL_SENSOR, defaults.manualSensorMode),
            manualStepFrequencyHz = (json.optDouble(K_MANUAL_STEP_FREQ, defaults.manualStepFrequencyHz.toDouble()).takeIf { it.isFinite() } ?: defaults.manualStepFrequencyHz.toDouble())
                .toFloat().coerceIn(0.1f, 6.0f),
            joystickSensitivity = (json.optDouble(K_JOYSTICK_SENSITIVITY, defaults.joystickSensitivity.toDouble()).takeIf { it.isFinite() } ?: defaults.joystickSensitivity.toDouble())
                .toFloat().coerceIn(0.2f, 2.0f),
            joystickSpeedMps = (json.optDouble(K_JOYSTICK_SPEED, defaults.joystickSpeedMps.toDouble()).takeIf { it.isFinite() } ?: defaults.joystickSpeedMps.toDouble())
                .toFloat().coerceIn(0.0f, 4.5f),
            motionSpeedMps = json.optDouble(K_MOTION_SPEED, Double.NaN).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 4.5f),
            joystickHeadingDeg = (json.optDouble(K_JOYSTICK_HEADING, defaults.joystickHeadingDeg.toDouble()).takeIf { it.isFinite() } ?: defaults.joystickHeadingDeg.toDouble())
                .toFloat().coerceIn(-180.0f, 180.0f),
            latitude = json.optDouble(K_LAT, defaults.latitude).takeIf { it.isFinite() } ?: defaults.latitude,
            longitude = json.optDouble(K_LNG, defaults.longitude).takeIf { it.isFinite() } ?: defaults.longitude,
            useAccuracy = json.optBoolean(K_USE_ACCURACY, defaults.useAccuracy),
            accuracy = json.optDouble(K_ACCURACY, defaults.accuracy.toDouble()).toFloat().coerceAtLeast(0f),
            useAltitude = json.optBoolean(K_USE_ALTITUDE, defaults.useAltitude),
            altitude = json.optDouble(K_ALTITUDE, defaults.altitude).takeIf { it.isFinite() } ?: defaults.altitude,
            useSpeed = json.optBoolean(K_USE_SPEED, defaults.useSpeed),
            speed = json.optDouble(K_SPEED, defaults.speed.toDouble()).toFloat().coerceAtLeast(0f),
            driftEnabled = json.optBoolean(K_DRIFT, defaults.driftEnabled),
            driftRadiusMeters = json.optDouble(K_DRIFT_RADIUS, defaults.driftRadiusMeters)
                .takeIf { it.isFinite() }?.coerceIn(0.0, 5_000.0) ?: defaults.driftRadiusMeters,
            driftSpeedMps = json.optDouble(K_DRIFT_SPEED, defaults.driftSpeedMps.toDouble())
                .toFloat().coerceIn(0f, 30f),
            antiMock = json.optBoolean(K_ANTI_MOCK, defaults.antiMock),
            strictSources = json.optBoolean(K_STRICT_SOURCES, defaults.strictSources),
            mapSdkCompat = json.optBoolean(K_MAP_SDK, defaults.mapSdkCompat),
            syncAddress = json.optBoolean(K_SYNC_ADDRESS, defaults.syncAddress),
            addrCountry = decodeText(json, K_ADDR_COUNTRY, defaults.addrCountry),
            addrProvince = decodeText(json, K_ADDR_PROVINCE, defaults.addrProvince),
            addrCity = decodeText(json, K_ADDR_CITY, defaults.addrCity),
            addrDistrict = decodeText(json, K_ADDR_DISTRICT, defaults.addrDistrict),
            addrTown = decodeText(json, K_ADDR_TOWN, defaults.addrTown),
            addrStreet = decodeText(json, K_ADDR_STREET, defaults.addrStreet),
            addrAdCode = decodeText(json, K_ADDR_ADCODE, defaults.addrAdCode),
            addrCityCode = decodeText(json, K_ADDR_CITYCODE, defaults.addrCityCode),
            addrPlaceName = decodeText(json, K_ADDR_PLACE, defaults.addrPlaceName),
            addrLine = decodeText(json, K_ADDR_LINE, defaults.addrLine),
            mapProviderId = decodeText(json, K_MAP_PROVIDER, defaults.mapProviderId),
            mapAkBaidu = decodeText(json, K_MAP_AK_BAIDU, defaults.mapAkBaidu),
            mapAkAmap = decodeText(json, K_MAP_AK_AMAP, defaults.mapAkAmap),
            mapAkTencent = decodeText(json, K_MAP_AK_TENCENT, defaults.mapAkTencent),
            mapSkTencent = decodeText(json, K_MAP_SK_TENCENT, defaults.mapSkTencent),
            places = decodePlaces(json.optJSONArray(K_PLACES)),
        )
    }

    /**
     * The history, as an array of flat objects.
     *
     * Flat rather than nested under an `"address"` key: the same eleven address
     * keys are already spelled out at the top level, and a second nested shape
     * would mean two decoders for one concept. Capped so a corrupt or hostile
     * blob cannot make the hook side walk a long list on every getter call.
     */
    private fun encodePlaces(places: List<SavedPlace>): JSONArray = JSONArray().apply {
        places.take(MAX_PLACES).forEach { place ->
            put(
                JSONObject().apply {
                    put(K_LAT, place.latitude)
                    put(K_LNG, place.longitude)
                    put(K_PINNED, place.pinned)
                    put(K_ADDR_COUNTRY, place.address.country)
                    put(K_ADDR_PROVINCE, place.address.province)
                    put(K_ADDR_CITY, place.address.city)
                    put(K_ADDR_DISTRICT, place.address.district)
                    put(K_ADDR_TOWN, place.address.town)
                    put(K_ADDR_STREET, place.address.street)
                    put(K_ADDR_ADCODE, place.address.adCode)
                    put(K_ADDR_CITYCODE, place.address.cityCode)
                    put(K_ADDR_PLACE, place.address.placeName)
                    put(K_ADDR_LINE, place.address.line)
                },
            )
        }
    }

    /**
     * Reads the history back, dropping anything unusable.
     *
     * A row with a non-finite or out-of-range coordinate is skipped rather than
     * clamped: a clamped row would silently move the user's saved office to
     * 90°N, and a stored place is only useful if it is exactly where they left
     * it.
     */
    private fun decodePlaces(array: JSONArray?): List<SavedPlace> {
        if (array == null) return emptyList()
        val count = minOf(array.length(), MAX_PLACES)
        val places = ArrayList<SavedPlace>(count)
        for (index in 0 until count) {
            val row = array.optJSONObject(index) ?: continue
            val latitude = row.optDouble(K_LAT, Double.NaN)
            val longitude = row.optDouble(K_LNG, Double.NaN)
            if (!latitude.isFinite() || !longitude.isFinite()) continue
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) continue
            places += SavedPlace(
                latitude = latitude,
                longitude = longitude,
                address = PickedAddress(
                    country = decodeText(row, K_ADDR_COUNTRY, ""),
                    province = decodeText(row, K_ADDR_PROVINCE, ""),
                    city = decodeText(row, K_ADDR_CITY, ""),
                    district = decodeText(row, K_ADDR_DISTRICT, ""),
                    town = decodeText(row, K_ADDR_TOWN, ""),
                    street = decodeText(row, K_ADDR_STREET, ""),
                    adCode = decodeText(row, K_ADDR_ADCODE, ""),
                    cityCode = decodeText(row, K_ADDR_CITYCODE, ""),
                    placeName = decodeText(row, K_ADDR_PLACE, ""),
                    line = decodeText(row, K_ADDR_LINE, ""),
                ),
                pinned = row.optBoolean(K_PINNED, false),
            )
        }
        return places
    }

    /**
     * Reads one free-text field.
     *
     * Trims, and treats a literal `"null"` as absent: `JSONObject.optString`
     * stringifies a JSON null into the four characters `null`, which would then
     * reach the user as a city literally named "null" — and, worse, count as a
     * *non-blank* value that the address hooks would hand straight to an app.
     *
     * Length-capped because these come back out of getters an app may call in a
     * loop, and the blob is written by a UI, not by a machine.
     */
    private fun decodeText(json: JSONObject, key: String, fallback: String): String {
        val value = json.optString(key, "").trim()
        if (value.isEmpty() || value == "null") return fallback
        return value.take(MAX_TEXT)
    }

    private fun decodeTargets(array: JSONArray?): Set<String> {
        if (array == null) return emptySet()
        return (0 until minOf(array.length(), 4096)).mapNotNull { index ->
            (array.opt(index) as? String)?.trim()?.takeIf {
                it.isNotEmpty() && it.length <= 255 && !it.any(Char::isWhitespace)
            }
        }.toSet()
    }

    /** Upper bound on any single stored text field. */
    private const val MAX_TEXT = 120

    /** Upper bound on the saved-place list, whatever a blob claims. */
    private const val MAX_PLACES = 32
    private fun encodeJog(p: JogProfile) = JSONObject().apply {
        put("enabled", p.enabled); put("cadence", p.cadenceSpm); put("min", p.cadenceMinSpm); put("max", p.cadenceMaxSpm)
        put("stride", p.strideMeters); put("stride_var", p.strideVariation); put("elasticity", p.speedElasticity)
        put("ramp_up", p.rampUpSeconds); put("ramp_down", p.rampDownSeconds); put("noise_seconds", p.fluctuationSeconds); put("seed", p.seed)
    }
    private fun decodeJog(j: JSONObject?): JogProfile {
        if (j == null) return JogProfile()
        val d = JogProfile()
        return JogProfile(j.optBoolean("enabled", d.enabled), j.optDouble("cadence", d.cadenceSpm),
            j.optDouble("min", d.cadenceMinSpm), j.optDouble("max", d.cadenceMaxSpm), j.optDouble("stride", d.strideMeters),
            j.optDouble("stride_var", d.strideVariation), j.optDouble("elasticity", d.speedElasticity),
            j.optDouble("ramp_up", d.rampUpSeconds), j.optDouble("ramp_down", d.rampDownSeconds),
            j.optDouble("noise_seconds", d.fluctuationSeconds), j.optLong("seed", d.seed)).validated()
    }
    private fun encodeGait(f: GaitFrame) = JSONObject().apply {
        put("session", f.session); put("ns", f.timestampNs); put("speed", f.speedMps); put("spm", f.cadenceSpm)
        put("stride", f.strideMeters); put("phase", f.phase); put("steps", f.steps); put("distance", f.distanceMeters)
        put("completed", f.completedMeters); put("remainder", f.remainderMeters); put("elapsed", f.elapsedSeconds)
        put("events", JSONArray().apply { f.recentSteps.forEach { e -> put(JSONArray().put(e.index).put(e.timestampNs).put(e.lengthMeters)) } })
    }
    private fun decodeGait(j: JSONObject?): GaitFrame? {
        if (j == null) return null
        return runCatching {
            val session = j.getLong("session"); val stamp = j.getLong("ns")
            fun number(k: String): Double = j.getDouble(k).also { require(it.isFinite() && it >= 0) }
            val steps = j.getLong("steps").also { require(it >= 0) }
            val f = GaitFrame(session, stamp, number("speed"), number("spm"), number("stride"), number("phase"), steps,
                number("distance"), number("completed"), number("remainder"), number("elapsed"),
                j.optJSONArray("events")?.let { a -> (maxOf(0, a.length() - 64) until a.length()).map { i ->
                    val row = a.getJSONArray(i)
                    GaitStep(row.getLong(0), row.getLong(1), row.getDouble(2)).also {
                        require(it.index in 1..steps && it.timestampNs in 1..stamp && it.lengthMeters.isFinite() && it.lengthMeters > 0)
                    }
                } } ?: emptyList())
            require(session > 0 && stamp > 0 && f.speedMps <= 4.5 && f.cadenceSpm <= 360 && f.phase < 1.000001)
            if (f.cadenceSpm > 0) require(kotlin.math.abs(f.speedMps - f.cadenceSpm / 60 * f.strideMeters) < 0.001)
            require(kotlin.math.abs(f.accountingError) < 0.001)
            require(f.recentSteps.zipWithNext().all { (a,b) -> b.index > a.index && b.timestampNs > a.timestampNs })
            f
        }.getOrNull()
    }

}
