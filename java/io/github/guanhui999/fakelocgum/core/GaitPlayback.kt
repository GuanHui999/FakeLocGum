package io.github.guanhui999.fakelocgum.core

/** Cursor over the manager's immutable step ledger. No second random clock. */
class GaitPlayback {
    private var session = 0L
    private var index = 0L
    private var emitted = 0L
    data class Output(val steps: Long, val timestamps: LongArray, val cadenceSpm: Double)

    fun read(frame: GaitFrame?, nowNs: Long, active: Boolean): Output {
        if (frame == null) return Output(emitted, longArrayOf(), 0.0)
        if (frame.session != session) {
            session = frame.session
            // New subscriptions must not replay an entire previous run.
            index = frame.recentSteps.lastOrNull { nowNs - it.timestampNs > 500_000_000L }?.index ?: 0L
        }
        if (!active || nowNs - frame.timestampNs !in 0..1_000_000_000L) {
            index = frame.steps
            return Output(emitted, longArrayOf(), 0.0)
        }
        val fresh = frame.recentSteps.filter { it.index > index && it.timestampNs <= nowNs }
        index = maxOf(index, frame.steps)
        emitted += fresh.size
        return Output(emitted, fresh.map { it.timestampNs }.toLongArray(), frame.cadenceSpm)
    }
}
