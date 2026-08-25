package io.github.christianherget.trackglance.bridge

import android.content.Context
import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import io.github.christianherget.trackglance.bridge.pebble.TrustAdmission
import io.github.christianherget.trackglance.bridge.pebble.TrustLeaseResult
import io.github.christianherget.trackglance.bridge.pebble.TrustedPebbleCompanionProvider
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleInfoRetriever
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class WatchAppLaunchResult {
    STARTED,
    NO_CONNECTED_WATCH,
    UNTRUSTED_COMPANION,
    STALE_COMPANION,
    LOOKUP_FAILED,
    LAUNCH_FAILED,
    TIMED_OUT,
}

internal class WatchAppLauncher(
    private val ensureTrusted: suspend () -> Boolean,
    private val captureAdmission: suspend () -> TrustAdmission?,
    private val underAdmission:
        suspend (
            TrustAdmission,
            suspend () -> WatchAppLaunchResult,
        ) -> TrustLeaseResult<WatchAppLaunchResult>,
    private val connectedWatchIds: suspend () -> List<String>,
    private val startWatchApp: suspend (List<String>) -> Unit,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    private val launchMutex = Mutex()

    init {
        require(timeoutMillis > 0)
    }

    suspend fun launch(): WatchAppLaunchResult = launchMutex.withLock {
        try {
            withTimeout(timeoutMillis) {
                if (!ensureTrusted()) return@withTimeout WatchAppLaunchResult.UNTRUSTED_COMPANION
                val admission =
                    captureAdmission()
                        ?: return@withTimeout WatchAppLaunchResult.UNTRUSTED_COMPANION
                when (
                    val gated =
                        underAdmission(admission) {
                            val watchIds =
                                try {
                                    connectedWatchIds()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: Exception) {
                                    return@underAdmission WatchAppLaunchResult.LOOKUP_FAILED
                                }
                            if (watchIds.isEmpty()) {
                                return@underAdmission WatchAppLaunchResult.NO_CONNECTED_WATCH
                            }
                            try {
                                startWatchApp(watchIds)
                                WatchAppLaunchResult.STARTED
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                WatchAppLaunchResult.LAUNCH_FAILED
                            }
                        }
                ) {
                    is TrustLeaseResult.Admitted -> gated.value
                    TrustLeaseResult.Stale -> WatchAppLaunchResult.STALE_COMPANION
                    TrustLeaseResult.Untrusted -> WatchAppLaunchResult.UNTRUSTED_COMPANION
                }
            }
        } catch (_: TimeoutCancellationException) {
            WatchAppLaunchResult.TIMED_OUT
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            WatchAppLaunchResult.LOOKUP_FAILED
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        fun create(context: Context): WatchAppLauncher {
            val appContext = context.applicationContext
            val pin = TrustedPebbleCompanionProvider.get(appContext)
            val retriever = DefaultPebbleInfoRetriever(appContext)
            val sender = DefaultPebbleSender(appContext)
            return WatchAppLauncher(
                ensureTrusted = pin::ensureTrustedBounded,
                captureAdmission = {
                    TrustedPebbleCompanionProvider.captureTrustedOutboundAdmission(appContext)
                },
                underAdmission = { admission, block ->
                    TrustedPebbleCompanionProvider.withOutboundAdmission(
                        appContext,
                        admission,
                        block,
                    )
                },
                connectedWatchIds = {
                    WATCH_LAUNCH_EXECUTOR.run {
                        runBlocking { retriever.getConnectedWatches().first().map { it.id.value } }
                    }
                },
                startWatchApp = { watchIds ->
                    WATCH_LAUNCH_EXECUTOR.run {
                        runBlocking {
                            sender.startAppOnTheWatch(
                                BridgeProtocol.APP_UUID,
                                watchIds.map(::WatchIdentifier),
                            )
                        }
                    }
                },
            )
        }

        private val WATCH_LAUNCH_EXECUTOR =
            BoundedAbandonableCallExecutor(
                maxWorkers = 2,
                threadNamePrefix = "watch-app-launch",
            )
    }
}
