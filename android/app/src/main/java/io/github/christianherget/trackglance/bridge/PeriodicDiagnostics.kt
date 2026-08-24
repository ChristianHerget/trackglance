package io.github.christianherget.trackglance.bridge

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal suspend fun runPeriodicDiagnostics(
    refresh: suspend () -> Unit,
    intervalMillis: Long = 5_000L,
    wait: suspend (Long) -> Unit = { delay(it) },
) {
    require(intervalMillis > 0)
    while (currentCoroutineContext().isActive) {
        refresh()
        wait(intervalMillis)
    }
}
