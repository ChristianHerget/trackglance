package io.github.christianherget.trackglance.bridge.core

enum class BridgeFailureKind {
    LOCUS_UNAVAILABLE,
    LOCUS_PROFILE_QUERY_FAILED,
    LOCUS_PROFILE_LIST_INVALID,
    LOCUS_RETURNED_NO_PROFILES,
    PEBBLE_WATCH_LOOKUP_FAILED,
    WATCHAPP_LAUNCH_FAILED,
    WATCHAPP_LAUNCH_TIMED_OUT,
    PEBBLE_COMPANION_UNTRUSTED,
    PEBBLE_COMPANION_NOT_INSTALLED,
    PEBBLE_COMPANION_NOT_SELECTED,
    WATCHAPP_INCOMPATIBLE,
    SNAPSHOT_DELIVERY_FAILED,
    COMMAND_RESULT_DELIVERY_FAILED,
    PROFILE_LIST_DELIVERY_FAILED,
    THIRD_PARTY_FAILURE,
}

data class BridgeFailure(
    val kind: BridgeFailureKind,
    val technicalDetail: String? = null,
) {
    companion object {
        fun technical(error: Throwable) =
            BridgeFailure(
                BridgeFailureKind.THIRD_PARTY_FAILURE,
                error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
            )

        fun technical(detail: String?) =
            BridgeFailure(
                BridgeFailureKind.THIRD_PARTY_FAILURE,
                detail?.takeIf(String::isNotBlank),
            )
    }
}
