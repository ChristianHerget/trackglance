package io.github.christianherget.locuspebble.bridge

import io.github.christianherget.locuspebble.bridge.core.RefreshMode
import io.github.christianherget.locuspebble.bridge.core.loadOffMain
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Safe-default UI state whose backing preference is read only when [load] is suspended off-main. */
internal class RefreshModePreferenceState(
    private val readPreference: () -> RefreshMode,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val selection = MutableStateFlow(RefreshMode.ADAPTIVE)

    suspend fun load() {
        selection.value = loadOffMain(ioDispatcher, readPreference)
    }

    fun select(mode: RefreshMode) {
        selection.value = mode
    }
}
