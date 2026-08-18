package io.github.christianherget.locuspebble.bridge.core

enum class RefreshMode { ADAPTIVE, FIVE_SECONDS, TEN_SECONDS }

class RefreshPolicy(private val mode: RefreshMode) {
    fun nextDelayMillis(transitioning: Boolean): Long = when (mode) {
        RefreshMode.ADAPTIVE -> if (transitioning) 2_000 else 10_000
        RefreshMode.FIVE_SECONDS -> 5_000
        RefreshMode.TEN_SECONDS -> 10_000
    }
}

