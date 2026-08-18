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

/**
 * PebbleKit preserves WATCH_ID through its asynchronous picker handoff, while it discards unknown
 * outer Bundle fields before invoking bridge callbacks. The NUL-prefixed envelope cannot collide
 * with a protocol-valid watch id and is always overwritten at the authenticated Binder entry.
 */
internal object AuthenticatedIngressEnvelope {
    private const val PREFIX = "\u0000locus-pebble-admission:"
    private const val SEPARATOR = '\u0000'
    private const val HEX_LENGTH = 16

    fun encode(watchId: String, admission: TrustAdmission): String = buildString {
        append(PREFIX)
        append(admission.generation.toString(16).padStart(HEX_LENGTH, '0'))
        append(SEPARATOR)
        append(watchId)
    }

    fun decode(encoded: WatchIdentifier): AuthenticatedWatchIngress? {
        val value = encoded.value
        if (!value.startsWith(PREFIX)) return null
        val separator = PREFIX.length + HEX_LENGTH
        if (value.length <= separator || value[separator] != SEPARATOR) return null
        val generation = value.substring(PREFIX.length, separator).toLongOrNull(16) ?: return null
        if (generation < 0) return null
        return AuthenticatedWatchIngress(
            watch = WatchIdentifier(value.substring(separator + 1)),
            admission = TrustAdmission(generation),
        )
    }
}
