package app.locuspebble.bridge.pebble

import android.content.Context
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

internal const val TRUSTED_CORE_APP_PACKAGE = "coredevices.coreapp"

/** Adds the bridge's exact CoreApp allowlist on top of PebbleKit's selected-app caller check. */
internal class TrustedPebbleCompanionGuard(
    private val initialized: () -> Boolean = { true },
    private val selectedPackage: suspend () -> String?,
) {
    suspend fun isTrusted(): Boolean = try {
        initialized() && selectedPackage() == TRUSTED_CORE_APP_PACKAGE
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

/** Synchronously establishes the picker invariant required by PebbleKit's Binder authentication. */
internal class TrustedPebbleCompanionPin(
    private val disableAutoSelect: () -> Unit,
    private val eligiblePackages: () -> List<String>,
    private val selectPackage: suspend (String) -> Unit,
    private val selectedPackage: suspend () -> String?,
) {
    private val initializationMutex = Mutex()

    @Volatile private var pinned = false

    val guard = TrustedPebbleCompanionGuard(
        initialized = { pinned },
        selectedPackage = selectedPackage,
    )

    suspend fun initialize(): Boolean = initializationMutex.withLock {
        pinned = false
        try {
            disableAutoSelect()
            if (TRUSTED_CORE_APP_PACKAGE !in eligiblePackages()) return@withLock false
            selectPackage(TRUSTED_CORE_APP_PACKAGE)
            (selectedPackage() == TRUSTED_CORE_APP_PACKAGE).also { pinned = it }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
    }

    suspend fun initializeBounded(timeoutMillis: Long = PIN_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0)
        return withTimeoutOrNull(timeoutMillis) { initialize() } ?: false
    }

    suspend fun ensureTrustedBounded(timeoutMillis: Long = PIN_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0)
        return withTimeoutOrNull(timeoutMillis) {
            guard.isTrusted() || initialize()
        } ?: false
    }

    fun initializeBlocking(): Boolean {
        if (pinned) return true
        return try {
            runBlocking(Dispatchers.IO) {
                initializeBounded()
            }
        } catch (_: Exception) {
            pinned = false
            false
        }
    }

    private companion object {
        const val PIN_TIMEOUT_MILLIS = 5_000L
    }
}

internal object TrustedPebbleCompanionProvider {
    @Volatile private var instance: TrustedPebbleCompanionPin? = null

    fun get(context: Context): TrustedPebbleCompanionPin = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(context: Context): TrustedPebbleCompanionPin {
        val picker = DefaultPebbleAndroidAppPicker.getInstance(context)
        return TrustedPebbleCompanionPin(
            disableAutoSelect = { picker.enableAutoSelect = false },
            eligiblePackages = picker::getAllEligibleApps,
            selectPackage = picker::selectApp,
            selectedPackage = picker::getCurrentlySelectedApp,
        )
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
