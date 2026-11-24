package io.github.guanhui999.fakelocgum.ui.map

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import io.github.guanhui999.fakelocgum.core.AddressLookupResult
import io.github.guanhui999.fakelocgum.core.MapProvider
import io.github.guanhui999.fakelocgum.core.PickedAddress
import io.github.guanhui999.fakelocgum.core.SpoofConfig
import io.github.guanhui999.fakelocgum.mapsdk.MapSdkBootstrap
import kotlinx.coroutines.*
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** One bounded fallback path for home retries. No guessed addresses or hidden keys. */
internal object AddressResolver {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FakeLoc-geocoder").apply { isDaemon = true }
    }

    suspend fun resolve(context: Context, config: SpoofConfig): AddressLookupResult {
        var result = AddressLookupResult.failure(-3)
        val key = config.mapKeyFor(MapProvider.TENCENT)
        if (key != null) {
            val ready = MapSdkBootstrap.ensure(context, config)
            if (ready is MapSdkBootstrap.Status.Failed) {
                result = AddressLookupResult(status = -1, detail = "地图 SDK 初始化失败，请到地图设置检查", needsSettings = true)
            } else {
                result = tencentReverseGeocodeDetailed(context, key, config.mapSecretValue(MapProvider.TENCENT),
                    config.latitude, config.longitude)
                if (result.success) return result
            }
        }
        val fallback = platform(context, config.latitude, config.longitude)
        return if (fallback?.isResolved == true) AddressLookupResult(address = fallback, detail = "系统地址服务解析成功") else result
    }

    private suspend fun platform(context: Context, latitude: Double, longitude: Double): PickedAddress? =
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                fun reply(found: Address?) {
                    val picked = found?.let { address ->
                        PickedAddress(country = address.countryName.orEmpty(), province = address.adminArea.orEmpty(),
                            city = address.locality.orEmpty().ifBlank { address.subAdminArea.orEmpty() },
                            district = address.subLocality.orEmpty(), street = address.thoroughfare.orEmpty(),
                            placeName = address.featureName.orEmpty(), line = address.getAddressLine(0).orEmpty())
                    }
                    if (continuation.isActive) continuation.resume(picked)
                }
                worker.execute {
                    if (!continuation.isActive) return@execute
                    try {
                        if (!Geocoder.isPresent()) { reply(null); return@execute }
                        val coder = Geocoder(context.applicationContext, Locale.getDefault())
                        if (Build.VERSION.SDK_INT >= 33) {
                            coder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) { reply(addresses.firstOrNull()) }
                                override fun onError(errorMessage: String?) { reply(null) }
                            })
                        } else {
                            @Suppress("DEPRECATION")
                            val found = coder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                            reply(found)
                        }
                    } catch (_: Exception) { reply(null) }
                }
            }
        }
}
