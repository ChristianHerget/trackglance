package io.github.christianherget.trackglance.bridge.core

/** Process-local acknowledgement state for recording-context delivery. */
internal class RecordingContextDeliveryTracker<Target> {
    class Attempt<Target>
    internal constructor(
        val target: Target,
        val profileId: String,
        internal val globalGeneration: Long,
        internal val targetGeneration: Long,
    )

    private val acknowledged = mutableMapOf<Target, String>()
    private val targetGenerations = mutableMapOf<Target, Long>()
    private var globalGeneration = 0L

    @Synchronized
    fun begin(target: Target, profileId: String): Attempt<Target>? =
        if (acknowledged[target] == profileId) {
            null
        } else {
            Attempt(target, profileId, globalGeneration, targetGenerations[target] ?: 0L)
        }

    @Synchronized
    fun commit(attempt: Attempt<Target>): Boolean {
        if (
            attempt.globalGeneration != globalGeneration ||
                attempt.targetGeneration != (targetGenerations[attempt.target] ?: 0L)
        )
            return false
        acknowledged[attempt.target] = attempt.profileId
        return true
    }

    @Synchronized
    fun invalidate(target: Target) {
        targetGenerations[target] = (targetGenerations[target] ?: 0L) + 1L
        acknowledged.remove(target)
    }

    @Synchronized
    fun invalidateAll() {
        globalGeneration++
        acknowledged.clear()
        targetGenerations.clear()
    }
}
