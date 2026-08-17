package app.locuspebble.bridge.core

import kotlinx.coroutines.CancellationException

/** Pure retry policy used by the Pebble transport adapter. */
class BoundedTargetDelivery<T>(
    private val maxAttempts: Int = 3,
    private val retryDelay: suspend (attempt: Int) -> Unit,
) {
    init {
        require(maxAttempts > 0)
    }

    suspend fun deliver(
        targets: Collection<T>,
        attempt: suspend (targets: List<T>) -> Set<T>,
    ): Boolean {
        val intended = targets.distinct()
        if (intended.isEmpty()) return true
        val remaining = intended.toMutableSet()
        repeat(maxAttempts) { zeroBasedAttempt ->
            val delivered = try {
                attempt(remaining.toList())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                emptySet()
            }
            remaining.removeAll(delivered)
            if (remaining.isEmpty()) return true
            if (zeroBasedAttempt + 1 < maxAttempts) retryDelay(zeroBasedAttempt + 1)
        }
        return false
    }
}
