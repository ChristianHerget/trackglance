package app.locuspebble.bridge.pebble

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val TRUSTED_CORE_APP_PACKAGE = "coredevices.coreapp"

/** Adds the bridge's exact CoreApp allowlist on top of PebbleKit's selected-app caller check. */
internal class TrustedPebbleCompanionGuard(
    private val selectedPackage: suspend () -> String?,
) {
    suspend fun isTrusted(): Boolean = try {
        selectedPackage() == TRUSTED_CORE_APP_PACKAGE
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

/** Preserves lifecycle callback order while their trust lookup may suspend. */
internal class SerializedTrustedLifecycleCallbacks(
    private val guard: TrustedPebbleCompanionGuard,
) {
    private val mutex = Mutex()

    suspend fun runIfTrusted(block: () -> Unit): Boolean = mutex.withLock {
        if (!guard.isTrusted()) return@withLock false
        block()
        true
    }
}
