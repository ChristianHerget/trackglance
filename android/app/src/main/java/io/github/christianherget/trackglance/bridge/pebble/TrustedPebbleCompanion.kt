package io.github.christianherget.trackglance.bridge.pebble

import android.content.Context
import android.content.pm.PackageManager
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal const val TRUSTED_CORE_APP_PACKAGE = "coredevices.coreapp"

internal enum class CoreAppConnectionKind {
    SELECTED,
    NOT_SELECTED,
    NOT_INSTALLED,
}

internal data class CoreAppConnectionStatus(
    val kind: CoreAppConnectionKind,
    val detail: String? = null,
) {
    val available: Boolean get() = kind == CoreAppConnectionKind.SELECTED
}

/**
 * Confirms that an exported Binder request came from the installed CoreApp package. Android already
 * enforces package-name uniqueness and update-signature matching; no additional certificate
 * enrollment is imposed by this local bridge.
 */
internal class TrustedCoreCallingUidVerifier(
    private val packagesForUid: (Int) -> Set<String>,
    private val coreApplicationUid: () -> Int,
) {
    fun isTrusted(uid: Int): Boolean = try {
        TRUSTED_CORE_APP_PACKAGE in packagesForUid(uid) && coreApplicationUid() == uid
    } catch (_: Exception) {
        false
    }
}

/** Keeps validation and dispatch atomic without retaining the lock across unrelated work. */
internal class SerializedCoreSessionLeases {
    private val inbound = Mutex()
    private val outbound = Mutex()

    suspend fun <Result> withInbound(block: suspend () -> Result): Result = inbound.withLock { block() }

    suspend fun <Result> withOutbound(block: suspend () -> Result): Result = outbound.withLock { block() }

    suspend fun <Result> mutateSession(block: suspend () -> Result): Result = inbound.withLock {
        outbound.withLock { block() }
    }
}

/** Requires PebbleKit to have the exact local CoreApp package selected. */
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

/** Establishes the exact CoreApp package selection off the main thread. */
internal class TrustedPebbleCompanionPin(
    private val disableAutoSelect: () -> Unit,
    private val eligiblePackages: () -> List<String>,
    private val selectPackage: suspend (String) -> Unit,
    private val selectedPackage: suspend () -> String?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    private val initializationMutex = Mutex()

    @Volatile private var pinned = false

    val guard = TrustedPebbleCompanionGuard(
        initialized = { pinned },
        selectedPackage = selectedPackage,
    )

    fun disableAutoSelection() = disableAutoSelect()

    fun invalidate() {
        pinned = false
    }

    suspend fun initialize(): Boolean = withContext(ioDispatcher) {
        initializationMutex.withLock {
            try {
                disableAutoSelect()
                if (selectedPackage() == TRUSTED_CORE_APP_PACKAGE) {
                    pinned = true
                    return@withLock true
                }
                pinned = false
                if (TRUSTED_CORE_APP_PACKAGE !in eligiblePackages()) return@withLock false
                selectPackage(TRUSTED_CORE_APP_PACKAGE)
                (selectedPackage() == TRUSTED_CORE_APP_PACKAGE).also { pinned = it }
            } catch (error: CancellationException) {
                pinned = false
                throw error
            } catch (_: Exception) {
                pinned = false
                false
            }
        }
    }

    suspend fun initializeBounded(timeoutMillis: Long = PIN_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0)
        return withTimeoutOrNull(timeoutMillis) { initialize() } ?: false
    }

    suspend fun ensureTrustedBounded(timeoutMillis: Long = PIN_TIMEOUT_MILLIS): Boolean {
        require(timeoutMillis > 0)
        return withTimeoutOrNull(timeoutMillis) { guard.isTrusted() || initialize() } ?: false
    }

    private companion object {
        const val PIN_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * Maintains a lightweight process-local connection epoch. It is not a certificate trust model; it
 * only prevents work captured before CoreApp selection was lost from resuming in a later session.
 */
internal object TrustedPebbleCompanionProvider {
    private data class Components(
        val pin: TrustedPebbleCompanionPin,
        val callingUidVerifier: TrustedCoreCallingUidVerifier,
    )

    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationLock = Any()
    private val sessionLeases = SerializedCoreSessionLeases()
    private val generation = AtomicLong()

    @Volatile private var components: Components? = null
    @Volatile private var initializationJob: Job? = null

    fun get(context: Context): TrustedPebbleCompanionPin = components(context).pin

    fun disableAutoSelection(context: Context) = get(context).disableAutoSelection()

    fun isTrustedCallingUid(context: Context, uid: Int): Boolean =
        components(context).callingUidVerifier.isTrusted(uid)

    fun initializeAsync(context: Context) {
        val pin = get(context)
        synchronized(initializationLock) {
            if (initializationJob?.isActive == true) return
            initializationJob = initializationScope.launch { pin.initializeBounded() }
        }
    }

    suspend fun inspect(context: Context): CoreAppConnectionStatus = withContext(Dispatchers.IO) {
        val installed = try {
            context.packageManager.getApplicationInfo(TRUSTED_CORE_APP_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (error: Exception) {
            return@withContext CoreAppConnectionStatus(
                CoreAppConnectionKind.NOT_INSTALLED,
                error.message ?: error.javaClass.simpleName,
            )
        }
        if (!installed) {
            CoreAppConnectionStatus(CoreAppConnectionKind.NOT_INSTALLED, "CoreApp is not installed")
        } else if (components(context).pin.guard.isTrusted()) {
            CoreAppConnectionStatus(CoreAppConnectionKind.SELECTED)
        } else {
            CoreAppConnectionStatus(
                CoreAppConnectionKind.NOT_SELECTED,
                "CoreApp is installed but could not be selected",
            )
        }
    }

    suspend fun captureTrustedAdmission(context: Context): TrustAdmission? {
        val candidate = TrustAdmission(generation.get())
        return when (withInboundAdmission(context, candidate) { Unit }) {
            is TrustLeaseResult.Admitted -> candidate
            TrustLeaseResult.Stale,
            TrustLeaseResult.Untrusted,
            -> null
        }
    }

    suspend fun captureTrustedOutboundAdmission(context: Context): TrustAdmission? {
        val candidate = TrustAdmission(generation.get())
        return when (withOutboundAdmission(context, candidate) { Unit }) {
            is TrustLeaseResult.Admitted -> candidate
            TrustLeaseResult.Stale,
            TrustLeaseResult.Untrusted,
            -> null
        }
    }

    suspend fun <Result> withInboundAdmission(
        context: Context,
        admission: TrustAdmission,
        block: suspend () -> Result,
    ): TrustLeaseResult<Result> = sessionLeases.withInbound {
        validateAdmission(context, admission, block)
    }

    suspend fun <Result> withOutboundAdmission(
        context: Context,
        admission: TrustAdmission,
        block: suspend () -> Result,
    ): TrustLeaseResult<Result> = sessionLeases.withOutbound {
        validateAdmission(context, admission, block)
    }

    fun isAdmissionCurrent(admission: TrustAdmission): Boolean =
        admission.generation == generation.get()

    private suspend fun <Result> validateAdmission(
        context: Context,
        admission: TrustAdmission,
        block: suspend () -> Result,
    ): TrustLeaseResult<Result> {
        if (!isAdmissionCurrent(admission)) return TrustLeaseResult.Stale
        if (!components(context).pin.guard.isTrusted()) {
            invalidateSession()
            return TrustLeaseResult.Untrusted
        }
        return TrustLeaseResult.Admitted(block())
    }

    private fun invalidateSession() {
        generation.incrementAndGet()
        components?.pin?.invalidate()
        io.github.christianherget.trackglance.bridge.core.BridgeRuntime.resetForCompanionTrustLoss()
    }

    private fun components(context: Context): Components = components ?: synchronized(this) {
        components ?: create(context.applicationContext).also { components = it }
    }

    @Suppress("DEPRECATION")
    private fun create(context: Context): Components {
        val picker = DefaultPebbleAndroidAppPicker.getInstance(context)
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = { picker.enableAutoSelect = false },
            eligiblePackages = picker::getAllEligibleApps,
            selectPackage = picker::selectApp,
            selectedPackage = picker::getCurrentlySelectedApp,
            ioDispatcher = Dispatchers.IO,
        )
        val callingUidVerifier = TrustedCoreCallingUidVerifier(
            packagesForUid = { uid -> context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty() },
            coreApplicationUid = {
                context.packageManager.getApplicationInfo(TRUSTED_CORE_APP_PACKAGE, 0).uid
            },
        )
        return Components(pin, callingUidVerifier)
    }
}

/** Preserves lifecycle callback order while CoreApp selection lookup may suspend. */
internal class SerializedTrustedLifecycleCallbacks(
    private val guard: TrustedPebbleCompanionGuard,
    private val onTrustLost: () -> Unit = {},
) {
    private val mutex = Mutex()

    suspend fun <Result> serialize(block: suspend () -> Result): Result = mutex.withLock { block() }

    suspend fun <Result> runIfTrusted(
        rejected: Result,
        block: suspend () -> Result,
    ): Result = serialize {
        if (!guard.isTrusted()) {
            onTrustLost()
            return@serialize rejected
        }
        block()
    }

    suspend fun runIfTrusted(block: () -> Unit): Boolean = runIfTrusted(false) {
        block()
        true
    }
}
