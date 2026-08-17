package app.locuspebble.bridge.pebble

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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

internal enum class CoreAppTrustKind {
    USER_APPROVED,
    APPROVAL_REQUIRED,
    NOT_INSTALLED,
    INVALID_PACKAGE,
}

internal data class InstalledPackageSigners(
    val current: Set<String>,
    val history: Set<String>,
)

internal data class CoreAppTrustStatus(
    val kind: CoreAppTrustKind,
    val currentSignerDigests: List<String> = emptyList(),
    val signerHistoryDigests: List<String> = emptyList(),
    val approvedSignerDigest: String? = null,
    val detail: String? = null,
) {
    val installed: Boolean get() = kind != CoreAppTrustKind.NOT_INSTALLED
    val trusted: Boolean get() = kind == CoreAppTrustKind.USER_APPROVED

    val enrollmentCandidate: String? get() = if (kind == CoreAppTrustKind.APPROVAL_REQUIRED) {
        currentSignerDigests.singleOrNull()
    } else {
        null
    }
}

internal object CoreAppSignerPolicy {
    fun evaluate(
        signers: InstalledPackageSigners?,
        approvedSignerDigest: String?,
    ): CoreAppTrustStatus {
        if (signers == null) {
            return CoreAppTrustStatus(
                kind = CoreAppTrustKind.NOT_INSTALLED,
                approvedSignerDigest = normalizeDigestOrNull(approvedSignerDigest),
                detail = "CoreApp is not installed",
            )
        }
        val current = signers.current.mapNotNull(::normalizeDigestOrNull).distinct().sorted()
        val history = (signers.history + signers.current)
            .mapNotNull(::normalizeDigestOrNull)
            .distinct()
            .sorted()
        val approved = normalizeDigestOrNull(approvedSignerDigest)
        if (current.isEmpty() || history.isEmpty()) {
            return CoreAppTrustStatus(
                kind = CoreAppTrustKind.INVALID_PACKAGE,
                currentSignerDigests = current,
                signerHistoryDigests = history,
                approvedSignerDigest = approved,
                detail = "CoreApp did not expose a valid signing certificate",
            )
        }
        // A single current signer plus Android's authenticated signing history permits key rotation,
        // while rejecting an unrelated reinstall that merely reuses the package name.
        val userApproved = current.size == 1 && approved != null && approved in history
        return CoreAppTrustStatus(
            kind = when {
                userApproved -> CoreAppTrustKind.USER_APPROVED
                else -> CoreAppTrustKind.APPROVAL_REQUIRED
            },
            currentSignerDigests = current,
            signerHistoryDigests = history,
            approvedSignerDigest = approved,
            detail = when {
                userApproved -> "Explicitly approved signer"
                current.size == 1 -> "Signer approval is required"
                else -> "CoreApp has multiple current signers and cannot be enrolled here"
            },
        )
    }

    fun normalizeDigestOrNull(value: String?): String? {
        val normalized = value?.replace(":", "")?.lowercase() ?: return null
        return normalized.takeIf { candidate ->
            candidate.length == SHA_256_HEX_LENGTH && candidate.all { it in '0'..'9' || it in 'a'..'f' }
        }
    }

    fun displayDigest(value: String): String = normalizeDigestOrNull(value)
        ?.chunked(2)
        ?.joinToString(":") { it.uppercase() }
        ?: value

    private const val SHA_256_HEX_LENGTH = 64
}

internal interface CoreAppSignerSource {
    fun installedSigners(): InstalledPackageSigners?
}

internal interface CoreAppSignerApprovalStorage {
    fun load(): String?
    fun save(digest: String?): Boolean
}

/**
 * Keeps authorization tied to a commit that actually reported success. SharedPreferences updates
 * its process-local map before disk I/O, even when commit() later returns false, so rereading the
 * preferences directly after a failed commit would accidentally authorize an unconfirmed value.
 */
internal class CommitConfirmedCoreAppSignerApprovalStorage(
    private val initialDigest: () -> String?,
    private val commitValue: (String?) -> Boolean,
) : CoreAppSignerApprovalStorage {
    private var initialized = false
    private var confirmedDigest: String? = null

    @Synchronized
    override fun load(): String? {
        if (!initialized) {
            confirmedDigest = CoreAppSignerPolicy.normalizeDigestOrNull(
                runCatching(initialDigest).getOrNull(),
            )
            initialized = true
        }
        return confirmedDigest
    }

    @Synchronized
    override fun save(digest: String?): Boolean {
        val normalized = if (digest == null) {
            null
        } else {
            CoreAppSignerPolicy.normalizeDigestOrNull(digest) ?: run {
                confirmedDigest = null
                return false
            }
        }
        val committed = runCatching { commitValue(normalized) }.getOrDefault(false)
        // Any ambiguous write fails closed, including a failed attempt to replace or revoke an
        // earlier approval. A later explicit successful save can establish a new confirmed value.
        initialized = true
        confirmedDigest = if (committed) normalized else null
        return committed
    }
}

internal class CoreAppTrustRepository(
    private val signerSource: CoreAppSignerSource,
    private val approvalStorage: CoreAppSignerApprovalStorage,
) {
    fun inspect(): CoreAppTrustStatus = try {
        CoreAppSignerPolicy.evaluate(signerSource.installedSigners(), approvalStorage.load())
    } catch (error: Exception) {
        CoreAppTrustStatus(
            kind = CoreAppTrustKind.INVALID_PACKAGE,
            approvedSignerDigest = runCatching { approvalStorage.load() }.getOrNull(),
            detail = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName,
        )
    }

    /** Saves only a digest that still belongs to the currently installed package. */
    fun approveCurrentSigner(digest: String): Boolean = try {
        val normalized = CoreAppSignerPolicy.normalizeDigestOrNull(digest) ?: return false
        val current = signerSource.installedSigners()?.current
            ?.mapNotNull(CoreAppSignerPolicy::normalizeDigestOrNull)
            ?.toSet()
            .orEmpty()
        current.size == 1 && normalized in current && approvalStorage.save(normalized)
    } catch (_: Exception) {
        false
    }

    fun clearApproval(): Boolean = runCatching { approvalStorage.save(null) }.getOrDefault(false)
}

internal class TrustedCoreCallingUidVerifier(
    private val packagesForUid: (Int) -> Set<String>,
    private val coreApplicationUid: () -> Int,
    private val signerTrusted: () -> Boolean,
) {
    fun isTrusted(uid: Int): Boolean = try {
        TRUSTED_CORE_APP_PACKAGE in packagesForUid(uid) &&
            coreApplicationUid() == uid &&
            signerTrusted()
    } catch (_: Exception) {
        false
    }
}

/** Gives operations sharing one trust domain a single revocation ordering point. */
internal class SerializedCoreTrustLease {
    private val mutex = Mutex()

    suspend fun <Result> withLease(block: suspend () -> Result): Result = mutex.withLock { block() }
}

/**
 * Serializes revocation with exact inbound mutation gates and outbound Binder requests. Trust
 * mutations always acquire inbound then outbound; ordinary runtime work carries a generation from
 * one gate to the other and never nests them.
 */
internal class SerializedCoreTrustLeases {
    private val inbound = SerializedCoreTrustLease()
    private val outbound = SerializedCoreTrustLease()

    suspend fun <Result> withInbound(block: suspend () -> Result): Result = inbound.withLease(block)

    suspend fun <Result> withOutbound(block: suspend () -> Result): Result = outbound.withLease(block)

    suspend fun <Result> mutateTrust(block: suspend () -> Result): Result = inbound.withLease {
        outbound.withLease(block)
    }
}

private class AndroidCoreAppSignerSource(
    private val packageManager: PackageManager,
) : CoreAppSignerSource {
    @Suppress("DEPRECATION")
    override fun installedSigners(): InstalledPackageSigners? {
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(
                    TRUSTED_CORE_APP_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES,
                )
            } else {
                packageManager.getPackageInfo(TRUSTED_CORE_APP_PACKAGE, PackageManager.GET_SIGNATURES)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val current: Array<out Signature>
        val history: Array<out Signature>
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo
                ?: return InstalledPackageSigners(emptySet(), emptySet())
            current = signingInfo.apkContentsSigners
            history = if (signingInfo.hasMultipleSigners()) {
                current
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            current = packageInfo.signatures.orEmpty()
            history = current
        }
        return InstalledPackageSigners(
            current = current.mapTo(mutableSetOf(), ::sha256),
            history = history.mapTo(mutableSetOf(), ::sha256),
        )
    }

    private fun sha256(signature: Signature): String = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private class SharedPreferencesCoreAppSignerApproval(context: Context) : CoreAppSignerApprovalStorage {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val confirmed = CommitConfirmedCoreAppSignerApprovalStorage(
        initialDigest = { preferences.getString(KEY_APPROVED_SIGNER, null) },
        commitValue = { normalized ->
            val editor = preferences.edit()
            if (normalized == null) {
                editor.remove(KEY_APPROVED_SIGNER)
            } else {
                editor.putString(KEY_APPROVED_SIGNER, normalized)
            }
            // This commit is the authorization barrier; do not replace it with asynchronous apply().
            @SuppressLint("UseKtx")
            editor.commit()
        },
    )

    override fun load(): String? = confirmed.load()

    override fun save(digest: String?): Boolean = confirmed.save(digest)

    private companion object {
        const val FILE = "core_app_trust"
        const val KEY_APPROVED_SIGNER = "approved_signer_sha256_v1"
    }
}

/** Adds signer validation and the exact CoreApp package pin to PebbleKit's selected-app caller check. */
internal class TrustedPebbleCompanionGuard(
    private val initialized: () -> Boolean = { true },
    private val signerTrusted: suspend () -> Boolean = { true },
    private val selectedPackage: suspend () -> String?,
) {
    suspend fun isTrusted(): Boolean = try {
        initialized() && selectedPackage() == TRUSTED_CORE_APP_PACKAGE && signerTrusted()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}

/** Establishes the picker invariant required by PebbleKit's Binder authentication off the main thread. */
internal class TrustedPebbleCompanionPin(
    private val disableAutoSelect: () -> Unit,
    private val eligiblePackages: () -> List<String>,
    private val selectPackage: suspend (String) -> Unit,
    private val selectedPackage: suspend () -> String?,
    private val signerTrusted: suspend () -> Boolean = { true },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
) {
    private val initializationMutex = Mutex()

    @Volatile private var pinned = false

    val guard = TrustedPebbleCompanionGuard(
        initialized = { pinned },
        selectedPackage = selectedPackage,
        signerTrusted = signerTrusted,
    )

    /** Must be called synchronously from Application.onCreate before an exported service may bind. */
    fun disableAutoSelection() = disableAutoSelect()

    /** Immediately fails every guard while durable trust state is being changed or revoked. */
    fun invalidate() {
        pinned = false
    }

    suspend fun initialize(): Boolean = withContext(ioDispatcher) {
        initializationMutex.withLock {
            try {
                disableAutoSelect()
                val current = selectedPackage()
                if (current == TRUSTED_CORE_APP_PACKAGE && signerTrusted()) {
                    pinned = true
                    return@withLock true
                }
                pinned = false
                if (!signerTrusted()) return@withLock false
                if (TRUSTED_CORE_APP_PACKAGE !in eligiblePackages()) return@withLock false
                selectPackage(TRUSTED_CORE_APP_PACKAGE)
                (selectedPackage() == TRUSTED_CORE_APP_PACKAGE && signerTrusted()).also { pinned = it }
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
        return withTimeoutOrNull(timeoutMillis) {
            guard.isTrusted() || initialize()
        } ?: false
    }

    private companion object {
        const val PIN_TIMEOUT_MILLIS = 5_000L
    }
}

internal object TrustedPebbleCompanionProvider {
    private data class Components(
        val pin: TrustedPebbleCompanionPin,
        val trust: CoreAppTrustRepository,
        val callingUidVerifier: TrustedCoreCallingUidVerifier,
    )

    private val initializationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializationLock = Any()
    private val trustLeases = SerializedCoreTrustLeases()
    private val trustPersistenceExecutor = BoundedAbandonableCallExecutor(
        maxWorkers = 1,
        threadNamePrefix = "core-trust-persistence",
    )
    private val trustMutations = CompanionTrustMutationCoordinator(
        leases = trustLeases,
        persistenceExecutor = trustPersistenceExecutor,
        invalidateRuntime = app.locuspebble.bridge.core.BridgeRuntime::resetForCompanionTrustLoss,
    )

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

    suspend fun inspect(context: Context): CoreAppTrustStatus = withContext(Dispatchers.IO) {
        components(context).trust.inspect()
    }

    suspend fun approveCurrentSigner(context: Context, digest: String): Boolean =
        withContext(Dispatchers.IO) {
            val values = components(context)
            val committed = trustMutations.mutate(
                admitAfterSuccess = true,
                invalidatePin = values.pin::invalidate,
                persist = { values.trust.approveCurrentSigner(digest) },
            )
            // Picker/PackageManager/DataStore calls are foreign synchronous work. Refresh in the
            // existing background scope and do not make this durable operation wait for them.
            if (committed) initializeAsync(context)
            committed
        }

    suspend fun clearApprovedSigner(context: Context): Boolean = withContext(Dispatchers.IO) {
        val values = components(context)
        val committed = trustMutations.mutate(
            admitAfterSuccess = false,
            invalidatePin = values.pin::invalidate,
            persist = values.trust::clearApproval,
        )
        // The pin was synchronously invalidated before persistence. A later approval/ensure call
        // may recover picker state; clearing never needs to wait for foreign picker work.
        committed
    }

    suspend fun captureTrustedAdmission(context: Context): TrustAdmission? {
        val candidate = TrustAdmission(trustMutations.generation)
        return when (withInboundAdmission(context, candidate) { Unit }) {
            is TrustLeaseResult.Admitted -> candidate
            TrustLeaseResult.Stale,
            TrustLeaseResult.Untrusted,
            -> null
        }
    }

    suspend fun captureTrustedOutboundAdmission(context: Context): TrustAdmission? {
        val candidate = TrustAdmission(trustMutations.generation)
        return when (withOutboundAdmission(context, candidate) { Unit }) {
            is TrustLeaseResult.Admitted -> candidate
            TrustLeaseResult.Stale,
            TrustLeaseResult.Untrusted,
            -> null
        }
    }

    /** Exact inbound mutation gate: stale generations never reset a newly approved identity. */
    suspend fun <Result> withInboundAdmission(
        context: Context,
        admission: TrustAdmission,
        block: suspend () -> Result,
    ): TrustLeaseResult<Result> = trustLeases.withInbound {
        validateAdmissionLocked(context, admission, block)
    }

    /** Atomically validates a token and retains the outbound lease through Binder dispatch. */
    suspend fun <Result> withOutboundAdmission(
        context: Context,
        admission: TrustAdmission,
        block: suspend () -> Result,
    ): TrustLeaseResult<Result> = trustLeases.withOutbound {
        validateAdmissionLocked(context, admission, block)
    }

    fun isAdmissionCurrent(admission: TrustAdmission): Boolean =
        trustMutations.isCurrent(admission)

    private suspend fun <Result> validateAdmissionLocked(
        context: Context,
        admission: TrustAdmission,
        block: suspend () -> Result,
    ): TrustLeaseResult<Result> {
        if (!isAdmissionCurrent(admission)) return TrustLeaseResult.Stale
        if (!components(context).pin.guard.isTrusted()) {
            app.locuspebble.bridge.core.BridgeRuntime.resetForCompanionTrustLoss()
            return TrustLeaseResult.Untrusted
        }
        return TrustLeaseResult.Admitted(block())
    }

    private fun components(context: Context): Components = components ?: synchronized(this) {
        components ?: create(context.applicationContext).also { components = it }
    }

    @Suppress("DEPRECATION")
    private fun create(context: Context): Components {
        val picker = DefaultPebbleAndroidAppPicker.getInstance(context)
        val trust = CoreAppTrustRepository(
            signerSource = AndroidCoreAppSignerSource(context.packageManager),
            approvalStorage = SharedPreferencesCoreAppSignerApproval(context),
        )
        val pin = TrustedPebbleCompanionPin(
            disableAutoSelect = { picker.enableAutoSelect = false },
            eligiblePackages = picker::getAllEligibleApps,
            selectPackage = picker::selectApp,
            selectedPackage = picker::getCurrentlySelectedApp,
            signerTrusted = {
                trustMutations.isAdmitted && withContext(Dispatchers.IO) { trust.inspect().trusted }
            },
            ioDispatcher = Dispatchers.IO,
        )
        val callingUidVerifier = TrustedCoreCallingUidVerifier(
            packagesForUid = { uid -> context.packageManager.getPackagesForUid(uid)?.toSet().orEmpty() },
            coreApplicationUid = {
                context.packageManager.getApplicationInfo(TRUSTED_CORE_APP_PACKAGE, 0).uid
            },
            signerTrusted = { trustMutations.isAdmitted && trust.inspect().trusted },
        )
        return Components(pin, trust, callingUidVerifier)
    }
}

/**
 * Linearizes only the in-memory authorization transition. Potentially blocking signer and storage
 * work runs on one bounded, abandonable worker after both operational leases have been released.
 */
internal class CompanionTrustMutationCoordinator(
    private val leases: SerializedCoreTrustLeases,
    private val persistenceExecutor: BoundedAbandonableCallExecutor,
    private val invalidateRuntime: () -> Unit,
    private val persistenceTimeoutMillis: Long = PERSISTENCE_TIMEOUT_MILLIS,
) {
    private val mutationInFlight = AtomicBoolean(false)
    private val generationCounter = AtomicLong()

    @Volatile private var admitted = true

    val isAdmitted: Boolean get() = admitted
    val generation: Long get() = generationCounter.get()

    fun isCurrent(admission: TrustAdmission): Boolean =
        admitted && admission.generation == generationCounter.get()

    suspend fun mutate(
        admitAfterSuccess: Boolean,
        invalidatePin: () -> Unit,
        persist: () -> Boolean,
    ): Boolean {
        if (!mutationInFlight.compareAndSet(false, true)) return false
        val token = try {
            leases.mutateTrust {
                admitted = false
                invalidatePin()
                generationCounter.incrementAndGet().also { invalidateRuntime() }
            }
        } catch (error: CancellationException) {
            mutationInFlight.set(false)
            throw error
        } catch (_: Exception) {
            mutationInFlight.set(false)
            return false
        }

        val completed = CompletableDeferred<Boolean>()
        val submitted = persistenceExecutor.execute {
            val persisted = runCatching(persist).getOrDefault(false)
            completed.complete(persisted)
            // Do not allow another mutation to overtake this durable write, even if its waiter
            // already timed out or was cancelled.
            mutationInFlight.set(false)
        }
        if (!submitted) {
            mutationInFlight.set(false)
            return false
        }
        val persisted = withTimeoutOrNull(persistenceTimeoutMillis) { completed.await() }
            ?: return false
        if (!persisted) return false

        return leases.mutateTrust {
            if (generationCounter.get() != token) return@mutateTrust false
            admitted = admitAfterSuccess
            true
        }
    }

    private companion object {
        const val PERSISTENCE_TIMEOUT_MILLIS = 5_000L
    }
}

/** Preserves lifecycle callback order while their trust lookup may suspend. */
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
