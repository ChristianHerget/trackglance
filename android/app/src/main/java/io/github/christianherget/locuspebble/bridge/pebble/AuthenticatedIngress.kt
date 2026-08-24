package io.github.christianherget.locuspebble.bridge.pebble

import io.rebble.pebblekit2.common.model.WatchIdentifier

/** Authorization epoch captured while the Core Binder caller identity is still available. */
@JvmInline
value class TrustAdmission(val generation: Long) {
    init {
        require(generation >= 0)
    }
}

internal sealed interface TrustLeaseResult<out Result> {
    data class Admitted<Result>(val value: Result) : TrustLeaseResult<Result>
    data object Stale : TrustLeaseResult<Nothing>
    data object Untrusted : TrustLeaseResult<Nothing>
}

internal data class AuthenticatedWatchIngress(
    val watch: WatchIdentifier,
    val admission: TrustAdmission,
)
