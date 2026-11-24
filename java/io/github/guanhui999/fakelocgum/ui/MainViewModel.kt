package io.github.guanhui999.fakelocgum.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.guanhui999.fakelocgum.App
import io.github.guanhui999.fakelocgum.R
import io.github.guanhui999.fakelocgum.core.Geo
import io.github.guanhui999.fakelocgum.core.MapProvider
import io.github.guanhui999.fakelocgum.core.PickedAddress
import io.github.guanhui999.fakelocgum.core.SavedPlace
import io.github.guanhui999.fakelocgum.core.SpoofConfig
import io.github.guanhui999.fakelocgum.core.goToPlace
import io.github.guanhui999.fakelocgum.core.moveTo
import io.github.guanhui999.fakelocgum.core.recordingAnchor
import io.github.guanhui999.fakelocgum.core.togglePinned
import io.github.guanhui999.fakelocgum.core.withAnchor
import io.github.guanhui999.fakelocgum.core.withResolvedAddress
import io.github.guanhui999.fakelocgum.data.RemoteStore
import io.github.guanhui999.fakelocgum.mapsdk.MapSdkBootstrap
import io.github.guanhui999.fakelocgum.ui.map.tencentReverseGeocode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CancellationException
import io.github.guanhui999.fakelocgum.ui.map.AddressResolver
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * What the address resolver is doing, for the one line under the coordinates.
 *
 * Four states rather than a boolean because the three ways an anchor can end up
 * without an address need three different things from the user, and collapsing
 * them is what made this failure mode read as "the module is broken" for an
 * entire afternoon:
 *
 *  - [RESOLVING] — a lookup is in flight; wait, there is nothing to fix.
 *  - [FAILED] — the key was sent and refused, or the network was down. The
 *    address is the user's to retry, and one tap does it.
 *  - [NO_KEY] — nothing was ever sent. No amount of retrying helps; the key is
 *    the missing piece and it lives in the settings page.
 *  - [SETTLED] — either the address is known, or the user turned address
 *    sync off and does not want a lookup at all.
 */
enum class AddressState {
    SETTLED,
    RESOLVING,
    FAILED,
    NO_KEY,
    WAITING,
    NEED_SETTINGS,
}

/**
 * Single source of truth for the manager UI.
 *
 * The config is held as a [MutableStateFlow] so Compose re-renders the instant a
 * switch flips, while the actual write to LSPosed's remote preferences happens
 * off the main thread — it is a synchronous `commit()` across a binder, and
 * running that on the UI thread would visibly stutter on a slider drag.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as App).store
    // Shared with the overlay service; never overwrite a newer remote snapshot from stale UI state.
    private val _config = store.config
    val config: StateFlow<SpoofConfig> = store.config
    val delivery = store.delivery

    private val _installedApps = MutableStateFlow<List<InstalledApp>>(emptyList())
    val installedApps: StateFlow<List<InstalledApp>> = _installedApps.asStateFlow()

    /** Transient messages, as string resource ids, for the snackbar. */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    /** `true` while LSPosed's service is bound. */
    val serviceBound: StateFlow<Boolean> = App.services
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.serviceAvailable)

    private var lastLocationRequestMs = 0L

    private val _addressState = MutableStateFlow(AddressState.SETTLED)
    private val _addressDetail = MutableStateFlow("")
    val addressDetail: StateFlow<String> = _addressDetail.asStateFlow()
    private var addressGeneration = 0L

    /** Drives the single line under the coordinates on the home screen. */
    val addressState: StateFlow<AddressState> = _addressState.asStateFlow()

    /** The lookup in flight, cancelled whenever the anchor moves again. */
    private var addressJob: Job? = null

    init {
        // A configuration can arrive without an address for a dozen reasons that
        // all predate this code — a hand-typed pair, a preset, a place recorded
        // from the history before anyone resolved its address. Starting here
        // means such a blob repairs itself the next time the manager is opened,
        // instead of needing the user to remember which screen fixes it.
        // Observe the shared store, including Service motion and changed map credentials.
        // Previously only ViewModel.update scheduled a lookup, so overlay movement was invisible.
        viewModelScope.launch {
            config.map { listOf(it.latitude, it.longitude, it.joystickSpeedMps > 0f,
                it.syncAddress, it.mapAkTencent, it.mapSkTencent) }.distinctUntilChanged().collect {
                scheduleAddress()
            }
        }
        refreshInstalledApps()
    }

    // ------------------------------------------------------------ config

    /**
     * Applies [transform] to the config: the UI updates immediately, the persist
     * happens in the background. A failed remote write surfaces as a message
     * rather than being swallowed, because a silently unsaved toggle is
     * indistinguishable from a broken module.
     */
    fun update(transform: (SpoofConfig) -> SpoofConfig) {
        val before = _config.value
        val next = store.update { current ->
            val transformed = transform(current)
            if (transformed.latitude != current.latitude || transformed.longitude != current.longitude)
                transformed.recordingAnchor() else transformed
        }
        // Address scheduling is owned by the config observer, including overlay updates.
    }

    /** Re-selects a place from the history, address and all. */
    fun pickSavedPlace(place: SavedPlace) {
        update { it.goToPlace(place) }
        _messages.tryEmit(R.string.toast_place_applied)
    }

    /**
     * Pins or unpins a saved place.
     *
     * Separate from [pickSavedPlace] on purpose: pinning must not move the
     * anchor. Pinning the place you are standing on while comparing it with
     * another is a reasonable thing to do, and a star that also teleported the
     * map would make that impossible.
     */
    fun togglePinned(latitude: Double, longitude: Double) {
        update { it.togglePinned(latitude, longitude) }
    }

    fun togglePlaying() {
        if (_config.value.selectedPackages.isEmpty() && !_config.value.playing) return
        update { it.copy(playing = !it.playing, joystickSpeedMps = 0f) }
    }

    /** Toggle one checkbox without clearing the other selected apps. */
    fun setTargetPackage(packageName: String) {
        val name = packageName.trim()
        if (name.isBlank()) return
        update { cfg -> cfg.withTargets(if (name in cfg.selectedPackages) cfg.selectedPackages - name else cfg.selectedPackages + name) }
    }

    fun setTargetPackages(packages: Set<String>) { update { it.withTargets(packages) } }
    fun clearTargetPackage() { update { it.withTargets(emptySet()) } }

    fun refreshInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val packageManager = getApplication<Application>().packageManager
            val apps = runCatching {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .asSequence()
                    .map { info ->
                        InstalledApp(
                            label = packageManager.getApplicationLabel(info).toString().ifBlank { info.packageName },
                            packageName = info.packageName,
                            system = (info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
                        )
                    }
                    .filter { it.packageName !in setOf("android", "system", "com.android.providers.settings",
                        "com.android.phone", "com.google.android.gms", getApplication<Application>().packageName) }
                    .distinctBy { it.packageName }
                    .sortedBy { it.label.lowercase(Locale.ROOT) }
                    .toList()
            }.getOrDefault(emptyList())
            _installedApps.value = apps
        }
    }

    /**
     * Validates the two text fields and adopts them.
     *
     * Kept separate from [setCoordinates] because this is the explicit "保存"
     * action and deserves a confirmation, whereas presets, paste and "use real
     * GPS" already give their own feedback.
     */
    fun commitCoordinates(latitudeText: String, longitudeText: String) {
        val latitude = latitudeText.trim().toDoubleOrNull()
        val longitude = longitudeText.trim().toDoubleOrNull()
        if (latitude == null || longitude == null ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            _messages.tryEmit(R.string.toast_invalid_coords)
            return
        }
        update { it.moveTo(latitude, longitude) }
        _messages.tryEmit(R.string.toast_saved)
    }

    fun setCoordinates(latitude: Double, longitude: Double) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            _messages.tryEmit(R.string.toast_invalid_coords)
            return
        }
        update { it.moveTo(latitude, longitude) }
    }

    /**
     * Adopts a spot chosen on the Tencent map.
     *
     * The picker has already converted GCJ-02 to WGS-84, so nothing is transformed
     * here. This exists so the map flow gets its own confirmation, the way
     * presets and paste have theirs — the user just spent a minute aiming and
     * deserves to be told it stuck.
     *
     * This is also the **only** path that carries an address. The picker
     * reverse-geocodes the centre, and those strings have to travel with the
     * coordinates: the module answers the SDKs' `getCity()` with them, and an app
     * that shows a city it looked up by name needs the administrative spelling.
     */
    fun applyMapPick(latitude: Double, longitude: Double, address: PickedAddress) {
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) {
            _messages.tryEmit(R.string.toast_invalid_coords)
            return
        }
        update { it.withAnchor(latitude, longitude, address) }
        _messages.tryEmit(R.string.toast_map_picked)
    }

    fun resetToDefaults() {
        // The saved places survive a reset. "Reset" means the spoofing
        // parameters, not the user's own history — and a reset that silently
        // deleted the office they had pinned would be the last thing they ever
        // used this button for.
        store.update { SpoofConfig(places = it.places) }
    }

    /** Explicit retry; service binds already trigger this on the Application. */
    fun reload() { store.reconnect() }

    // ------------------------------------------------------------ address

    /**
     * Makes sure the anchor has an address, by reverse-geocoding it when it does
     * not.
     *
     * ## Why this is not left to the picker
     *
     * Only one of the six ways to set an anchor carries an address — the map
     * picker, through [applyMapPick]. The other five ([commitCoordinates],
     * [setCoordinates], [applyClipboard], [useRealLocation] and re-selecting a
     * history entry) call `moveTo`, which deliberately drops the old address
     * because it describes different coordinates. The address was therefore
     * blank in every configuration that did not end in a map pick, and a blank
     * address is not a cosmetic gap:
     *
     *  - the SDK getters answer nothing, so a target app that prints its own
     *    city keeps the one it cached from the last real fix;
     *  - CCB's employee app answers its check-in page from `getAddrStr()` and
     *    renders "地理位置获取失败" when it is empty — while the map, the
     *    coordinates and the injected fix are all perfectly correct (measured
     *    2026-11-12, config with every address field blank).
     *
     * A user who set the anchor by typing a pair or by tapping a preset has no
     * way to know that; the symptom points at everything except the cause.
     *
     * ## Debounce
     *
     * Every step of the coordinate editor goes through [update], so dragging a
     * slider or finishing a typed number would otherwise fire a request per
     * keystroke. The delay is cancelled and restarted, so only the value the
     * user settles on is looked up.
     */
    private fun scheduleAddress(delayMs: Long = ADDRESS_DEBOUNCE_MS, force: Boolean = false) {
        addressJob?.cancel()
        val generation = ++addressGeneration
        val current = _config.value
        if (!current.syncAddress) {
            _addressState.value = AddressState.SETTLED
            _addressDetail.value = "地址同步已关闭，不影响坐标模拟"
            return
        }
        if (current.joystickSpeedMps > 0f) {
            _addressState.value = AddressState.WAITING
            _addressDetail.value = "移动中，松开摇杆后自动解析地址"
            return
        }
        if (current.addressKnown && !force) {
            _addressState.value = AddressState.SETTLED
            _addressDetail.value = ""
            return
        }
        _addressState.value = AddressState.RESOLVING
        _addressDetail.value = "正在解析地址…"
        addressJob = viewModelScope.launch {
            try {
                delay(delayMs)
                val result = AddressResolver.resolve(getApplication(), current)
                if (generation != addressGeneration) return@launch
                val now = _config.value
                // A result for the old point is not success for the new point. The
                // config observer owns rescheduling, so no permanently stuck RESOLVING.
                if (now.latitude != current.latitude || now.longitude != current.longitude || now.joystickSpeedMps > 0f) {
                    _addressState.value = AddressState.WAITING
                    _addressDetail.value = "坐标已变化，等待新位置解析"
                    return@launch
                }
                val address = result.address
                if (result.success && address != null) {
                    store.update { it.withResolvedAddress(address) }
                    _addressState.value = AddressState.SETTLED
                    _addressDetail.value = result.detail
                } else {
                    _addressState.value = if (result.needsSettings) AddressState.NEED_SETTINGS else AddressState.FAILED
                    _addressDetail.value = result.detail + "；坐标模拟不受影响"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == addressGeneration) {
                    _addressState.value = AddressState.FAILED
                    _addressDetail.value = "解析异常（${error.javaClass.simpleName}），可重试；不影响坐标模拟"
                }
            }
        }
    }

    /** Force really sends a new request; cached/partial address cannot turn Retry into a no-op. */
    fun retryAddress() = scheduleAddress(delayMs = 0, force = true)

    // ------------------------------------------------------------ actions

    /**
     * Reads the clipboard and adopts whatever coordinate it contains. Accepts a
     * bare `"lat, lng"`, a shared map link, or a `geo:` URI — see
     * [Geo.parseCoordinates].
     */
    fun applyClipboard(text: String?) {
        val parsed = Geo.parseCoordinates(text)
        if (parsed == null) {
            _messages.tryEmit(R.string.toast_paste_failed)
            return
        }
        update { it.moveTo(parsed.first, parsed.second) }
        _messages.tryEmit(R.string.toast_paste_ok)
    }

    /**
     * Uses the device's own position as the anchor — useful for picking a spot
     * "a few hundred metres from here" without typing coordinates.
     *
     * Requires `ACCESS_FINE_LOCATION`; the caller is responsible for having
     * requested it, since a permission prompt can only be launched from an
     * Activity.
     */
    @SuppressLint("MissingPermission")
    fun useRealLocation() {
        val context = getApplication<Application>()

        val fine = ContextCompat.checkSelfPermission(
            context,
            "android.permission.ACCESS_FINE_LOCATION",
        ) == PackageManager.PERMISSION_GRANTED

        val coarse = ContextCompat.checkSelfPermission(context,
            "android.permission.ACCESS_COARSE_LOCATION") == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            _messages.tryEmit(R.string.toast_location_denied)
            return
        }

        // Throttle: this starts a real fix on the radio, and a user tapping the
        // button repeatedly should not queue up several of them.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLocationRequestMs < LOCATION_THROTTLE_MS) return
        lastLocationRequestMs = now

        val manager = context.getSystemService(LocationManager::class.java) ?: run {
            _messages.tryEmit(R.string.toast_location_failed)
            return
        }

        val provider = when {
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            fine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.PASSIVE_PROVIDER
        }

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.getCurrentLocation(
                    provider,
                    null,
                    ContextCompat.getMainExecutor(context),
                ) { location: Location? ->
                    if (location == null) {
                        _messages.tryEmit(R.string.toast_location_failed)
                    } else {
                        update { it.moveTo(location.latitude, location.longitude) }
                    }
                }
            } else {
                // API 29 has no async one-shot API; the last known fix is the only
                // thing available without registering a listener.
                val last = manager.getLastKnownLocation(provider)
                if (last == null) {
                    _messages.tryEmit(R.string.toast_location_failed)
                } else {
                    update { it.moveTo(last.latitude, last.longitude) }
                }
            }
        }.onFailure {
            _messages.tryEmit(R.string.toast_location_failed)
        }
    }

    private companion object {
        const val LOCATION_THROTTLE_MS = 5_000L

        /**
         * How long the anchor has to sit still before it is looked up.
         *
         * Long enough to swallow a slider drag or a typed number (the editor
         * commits on every valid keystroke), short enough that the address is
         * there by the time the user has read the coordinates.
         */
        const val ADDRESS_DEBOUNCE_MS = 700L
    }
}
