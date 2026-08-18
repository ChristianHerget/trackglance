package io.github.christianherget.locuspebble.bridge.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Explicit boundary for cold storage and runtime construction that must not run on a UI caller. */
internal suspend fun <Result> loadOffMain(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: () -> Result,
): Result = withContext(dispatcher) { block() }
