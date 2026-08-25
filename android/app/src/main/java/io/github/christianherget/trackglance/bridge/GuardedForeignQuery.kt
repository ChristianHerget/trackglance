package io.github.christianherget.trackglance.bridge

import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import io.github.christianherget.trackglance.bridge.pebble.TrustAdmission
import io.github.christianherget.trackglance.bridge.pebble.TrustLeaseResult
import kotlinx.coroutines.withTimeoutOrNull

internal enum class GuardedForeignQueryOutcome {
    PUBLISHED,
    STALE,
    UNTRUSTED,
    FAILED,
}

/**
 * Executes a potentially non-preemptible read after a short admission and publishes it only if the
 * same trust generation is still live. Cancellation abandons, rather than joins, the worker.
 */
internal suspend fun <Result> generationGuardedForeignQuery(
    executor: BoundedAbandonableCallExecutor,
    timeoutMillis: Long,
    admit: suspend () -> TrustAdmission?,
    query: () -> Result,
    publishIfCurrent: suspend (TrustAdmission, Result) -> TrustLeaseResult<Unit>,
): GuardedForeignQueryOutcome {
    val admission = admit() ?: return GuardedForeignQueryOutcome.UNTRUSTED
    val result =
        withTimeoutOrNull(timeoutMillis) { executor.run(query) }
            ?: return GuardedForeignQueryOutcome.FAILED
    return when (publishIfCurrent(admission, result)) {
        is TrustLeaseResult.Admitted -> GuardedForeignQueryOutcome.PUBLISHED
        TrustLeaseResult.Stale -> GuardedForeignQueryOutcome.STALE
        TrustLeaseResult.Untrusted -> GuardedForeignQueryOutcome.UNTRUSTED
    }
}
