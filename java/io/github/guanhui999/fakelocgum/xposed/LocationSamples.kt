package io.github.guanhui999.fakelocgum.xposed

import android.location.Location
import android.os.SystemClock

/**
 * A bounded, process-local cache of ORIGINAL provider metadata. A synthetic update
 * used to discard extras/hasAltitude/accuracy and could make navigation report GPS
 * lost despite a healthy receiver. Never invent satellite counts to fill a gap.
 */
internal object LocationSamples {
    private data class Sample(val location: Location, val receivedMs: Long)
    private val samples = LinkedHashMap<String, Sample>()
    private val synthetic = ThreadLocal<Boolean>()

    @Synchronized
    fun observe(location: Location) {
        if (synthetic.get() == true) return
        val provider = location.provider?.takeIf { it.isNotBlank() } ?: return
        val now = SystemClock.elapsedRealtime()
        samples[provider] = Sample(Location(location), now)
        while (samples.size > 8) samples.remove(samples.keys.first())
    }

    @Synchronized
    fun recent(provider: String?): Location? {
        val sample = samples[provider] ?: if (provider == null || provider == "fused" || provider == "passive") {
            samples.values.maxByOrNull { it.receivedMs }
        } else null
        sample ?: return null
        val age = SystemClock.elapsedRealtime() - sample.receivedMs
        if (age !in 0..60_000L) return null
        // Return a copy: applying virtual coordinates must never mutate the sample.
        return Location(sample.location)
    }

    @Synchronized
    fun invalidate(provider: String) { samples.remove(provider) }

    fun isSynthetic(): Boolean = synthetic.get() == true

    fun deliver(block: () -> Unit) {
        val previous = synthetic.get()
        synthetic.set(true)
        try { block() } finally { synthetic.set(previous) }
    }
}
