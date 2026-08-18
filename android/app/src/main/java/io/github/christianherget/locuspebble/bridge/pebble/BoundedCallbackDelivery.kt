package io.github.christianherget.locuspebble.bridge.pebble

import io.github.christianherget.locuspebble.bridge.core.BoundedAbandonableCallExecutor

/** Keeps a hostile two-way callback from blocking PebbleKit's MainScope or a Binder thread. */
internal class BoundedCallbackDelivery(
    private val executor: BoundedAbandonableCallExecutor,
) {
    fun deliver(
        stillAuthorized: () -> Boolean,
        callback: () -> Unit,
    ): Boolean = executor.execute {
        if (stillAuthorized()) runCatching(callback)
    }
}
