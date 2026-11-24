package io.github.guanhui999.fakelocgum.data

import android.content.Context
import android.util.Log
import io.github.guanhui999.fakelocgum.App
import io.github.guanhui999.fakelocgum.core.ConfigCodec
import io.github.guanhui999.fakelocgum.core.Keys
import io.github.guanhui999.fakelocgum.core.SpoofConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.Executors

enum class DeliveryState { LOCAL_ONLY, PENDING, SENT, FAILED }

/**
 * One manager-process store for both Activity and overlay Service. Repair: a
 * coroutine per slider event could commit an old START after the newer STOP.
 * All disk/Binder writes now share one ordered queue and the existing mirror.
 */
class RemoteStore(context: Context) {
    private val local = context.applicationContext.getSharedPreferences("fakeloc_local", Context.MODE_PRIVATE)
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { task -> Thread(task, "FakeLoc-config-writer") }
    private var revision = 0L
    private var edited = false
    private val original = ConfigCodec.decode(local.getString(Keys.CONFIG, null))
    // A touch vector is not a durable instruction: never resume walking after process death.
    private val state = MutableStateFlow(original.copy(joystickSpeedMps = 0f, motionSpeedMps = 0f, gaitFrame = null))
    val config = state.asStateFlow()
    private val deliveryState = MutableStateFlow(DeliveryState.LOCAL_ONLY)
    val delivery = deliveryState.asStateFlow()
    val serviceAvailable: Boolean get() = App.service != null

    fun load(): SpoofConfig = state.value

    fun update(transform: (SpoofConfig) -> SpoofConfig): SpoofConfig = change(false, transform)

    /** Motion is a stream, not a new START request on every 100 ms frame. */
    fun updateMotion(transform: (SpoofConfig) -> SpoofConfig): SpoofConfig = change(true, transform)

    private fun change(motionFrame: Boolean, transform: (SpoofConfig) -> SpoofConfig): SpoofConfig = synchronized(lock) {
        val before = state.value
        val changed = transform(before)
        val next = if (!changed.playing || changed.selectedPackages.isEmpty()) {
            changed.copy(joystickSpeedMps = 0f, motionSpeedMps = 0f,
                gaitFrame = changed.gaitFrame?.copy(speedMps = 0.0, cadenceSpm = 0.0, strideMeters = 0.0))
        } else changed
        if (before == next) return@synchronized before
        edited = true
        state.value = next
        val number = ++revision
        // Keep an established stream visually stable; failures are still reported.
        // Previously PENDING/SENT at 10 Hz toggled the entire status card and glow.
        if ((!motionFrame && (before.playing != next.playing || before.selectedPackages != next.selectedPackages)) ||
            deliveryState.value != DeliveryState.SENT) {
            deliveryState.value = DeliveryState.PENDING
        }
        writer.execute { persist(next, number) }
        next
    }

    /** Called on every actual service bind, not just a false -> true UI transition. */
    fun reconnect() {
        writer.execute {
            val pair = synchronized(lock) {
                // The existing local mirror is authoritative after an edit or failed send.
                // Only import the remote copy on a genuinely empty first installation.
                if (!edited && !local.contains(Keys.CONFIG)) {
                    runCatching { App.service?.getRemotePreferences(Keys.REMOTE_GROUP)?.getString(Keys.CONFIG, null) }
                        .onSuccess { raw -> if (raw != null) state.value = ConfigCodec.decode(raw).copy(joystickSpeedMps = 0f, motionSpeedMps = 0f, gaitFrame = null) }
                        .onFailure { Log.e(TAG, "remote initial read failed group=${Keys.REMOTE_GROUP}", it) }
                }
                state.value to revision
            }
            persist(pair.first, pair.second)
        }
    }

    fun disconnected() { deliveryState.value = DeliveryState.LOCAL_ONLY }

    private fun persist(snapshot: SpoofConfig, number: Long) {
        if (synchronized(lock) { number != revision }) return
        try {
            val json = ConfigCodec.encode(snapshot)
            val token = UUID.randomUUID().toString()
            check(local.edit().putString(Keys.CONFIG, json).putBoolean(PENDING, true).commit()) {
                "local mirror commit failed"
            }
            val service = App.service
            if (service == null) {
                report(number, DeliveryState.LOCAL_ONLY)
                Log.w(TAG, "local-only group=${Keys.REMOTE_GROUP} enabled=${snapshot.playing} targets=${snapshot.selectedPackages.size}")
                return
            }
            val prefs = service.getRemotePreferences(Keys.REMOTE_GROUP)
            // libxposed updates its local cache BEFORE committing the Binder write.
            // Each retry uses a new editor and resends CONFIG; revision identifies its notification.
            val sent = prefs.edit().putString(Keys.CONFIG, json).putString(Keys.REVISION, token).commit()
            if (sent) {
                local.edit().putBoolean(PENDING, false).apply()
                report(number, DeliveryState.SENT)
                Log.i(TAG, "sent revision=$number group=${Keys.REMOTE_GROUP} key=${Keys.CONFIG} enabled=${snapshot.playing} targets=${snapshot.selectedPackages.size}")
            } else {
                report(number, DeliveryState.FAILED)
                Log.e(TAG, "remote commit=false revision=$number; mirror retained for retry")
            }
        } catch (error: Throwable) {
            report(number, DeliveryState.FAILED)
            Log.e(TAG, "configuration delivery failed revision=$number", error)
        }
    }

    private fun report(number: Long, result: DeliveryState) = synchronized(lock) {
        if (number == revision) deliveryState.value = result
    }

    private companion object {
        const val TAG = "FakeLocGum/RemoteStore"
        const val PENDING = "pending_remote_write"
    }
}
