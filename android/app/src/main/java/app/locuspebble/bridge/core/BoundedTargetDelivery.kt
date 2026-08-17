package app.locuspebble.bridge.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Pure retry policy used by the Pebble transport adapter. */
class BoundedTargetDelivery<T>(
    private val maxAttempts: Int = 3,
    private val attemptTimeoutMillis: Long = 10_000L,
    private val retryDelay: suspend (attempt: Int) -> Unit,
) {
    init {
        require(maxAttempts > 0)
        require(attemptTimeoutMillis > 0)
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
                withTimeoutOrNull(attemptTimeoutMillis) {
                    attempt(remaining.toList())
                }.orEmpty()
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
