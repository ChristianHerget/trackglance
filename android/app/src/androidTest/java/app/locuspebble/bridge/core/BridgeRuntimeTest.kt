package app.locuspebble.bridge.core

import app.locuspebble.bridge.locus.LocusBridgeGateway
import app.locuspebble.bridge.pebble.PebbleDictionarySender
import app.locuspebble.bridge.pebble.PebbleMessages
import app.locuspebble.bridge.pebble.ReliablePebbleTransport
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRuntimeTest {
    @Test fun openingAndClosingMultipleWatchesPreservesTheActiveLifecycle() {
        val sender = RecordingSender()
        val runtime = runtime(sender = sender)
        val watchA = WatchIdentifier("watch-a")
        val watchB = WatchIdentifier("watch-b")
        try {
            runtime.watchAppOpened(watchA)
            runtime.watchAppOpened(watchB)
            assertTrue(BridgeState.status.value.watchAppOpen)
            assertEquals(listOf(watchA), sender.calls[0].watches)
            assertEquals(listOf(watchB), sender.calls[1].watches)

            runtime.watchAppClosed(watchA)
            assertTrue(BridgeState.status.value.watchAppOpen)
            runtime.watchAppClosed(watchB)
            assertFalse(BridgeState.status.value.watchAppOpen)
        } finally {
            runtime.close()
        }
    }

    @Test fun inboundMessageAfterProcessRestartRecoversTheOpenWatchLifecycle() {
        val sender = RecordingSender()
        val runtime = runtime(sender = sender)
        val watch = WatchIdentifier("watch-after-restart")
        try {
            runtime.watchObserved(watch)
            assertTrue(BridgeState.status.value.watchAppOpen)
            assertEquals(listOf(watch), sender.calls.single().watches)
        } finally {
            runtime.watchAppClosed(watch)
            runtime.close()
        }
    }

    @Test fun commandResultsAndRefreshesReturnOnlyToTheirSourceWatch() = runBlocking {
        val sender = RecordingSender()
        val locus = FakeLocus()
        val runtime = runtime(sender = sender, locus = locus)
        val watchA = WatchIdentifier("watch-a")
        val watchB = WatchIdentifier("watch-b")
        try {
            assertTrue(runtime.handleCommand(watchA, 7, 1, BridgeProtocol.Command.STOP_SAVE, null, null))
            assertTrue(runtime.handleCommand(watchA, 7, 1, BridgeProtocol.Command.STOP_SAVE, "ignored", null))
            assertTrue(runtime.handleCommand(watchB, 7, 1, BridgeProtocol.Command.STOP_SAVE, null, null))

            assertEquals(2, locus.executions)
            assertTrue(sender.calls.take(4).all { it.watches == listOf(watchA) })
            assertTrue(sender.calls.drop(4).take(2).all { it.watches == listOf(watchB) })

            assertTrue(runtime.handleCommand(watchA, 7, 1, BridgeProtocol.Command.START, "Hiking", null))
            assertEquals(2, locus.executions)
            assertEquals(
                BridgeProtocol.Result.FAILED.wire,
                PebbleMessages.signed32(sender.calls[sender.calls.lastIndex - 1].dictionary, BridgeProtocol.Key.RESULT),
            )
        } finally {
            runtime.close()
        }
    }

    @Test fun heartRateConsumerSurvivesOneSampleFailureAndRoutesTheNextUpdate() {
        val sender = RecordingSender()
        val locus = FakeLocus(heartRateFailures = 1)
        val runtime = runtime(sender = sender, locus = locus)
        val watch = WatchIdentifier("heart-rate-watch")
        try {
            assertTrue(runtime.handleHeartRate(watch, 9, 1, 120, 1_000))
            assertTrue(runtime.handleHeartRate(watch, 9, 2, 121, 1_000))

            assertEquals(2, locus.heartRateCalls)
            assertEquals(1, sender.calls.size)
            assertEquals(listOf(watch), sender.calls.single().watches)
        } finally {
            runtime.close()
        }
    }

    @Test fun profileTransfersAreSerializedAndEachTransferHasOneTarget() = runBlocking {
        val sender = RecordingSender(yieldDuringSend = true)
        val locus = FakeLocus(profiles = listOf("x".repeat(200)))
        val runtime = runtime(sender = sender, locus = locus)
        val watchA = WatchIdentifier("watch-a")
        val watchB = WatchIdentifier("watch-b")
        try {
            coroutineScope {
                launch { assertTrue(runtime.sendRecordingProfiles(watchA)) }
                launch { assertTrue(runtime.sendRecordingProfiles(watchB)) }
            }

            val transferIds = sender.calls.map {
                PebbleMessages.signed32(it.dictionary, BridgeProtocol.Key.TRANSFER_ID)
            }
            assertEquals(2, transferIds.distinct().size)
            assertEquals(2, transferIds.zipWithNext().count { (first, second) -> first != second } + 1)
            transferIds.distinct().forEach { transferId ->
                val targets = sender.calls.filter {
                    PebbleMessages.signed32(it.dictionary, BridgeProtocol.Key.TRANSFER_ID) == transferId
                }.flatMap { it.watches }.distinct()
                assertEquals(1, targets.size)
            }
        } finally {
            runtime.close()
        }
    }

    private fun runtime(
        sender: RecordingSender,
        locus: FakeLocus = FakeLocus(),
    ): BridgeRuntime = BridgeRuntime(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        locus = locus,
        transport = ReliablePebbleTransport(sender, maxAttempts = 1),
        commandJournal = CommandJournal(MemoryStorage()),
        refreshMode = { RefreshMode.ADAPTIVE },
        ioDispatcher = Dispatchers.Unconfined,
        monotonicMillis = { 1_000L },
        wallMillis = { 1_000_000L },
        delayMillis = { duration -> if (duration >= 2_000L) delay(Long.MAX_VALUE) },
        initialTransferId = 100,
    )

    private class FakeLocus(
        private var heartRateFailures: Int = 0,
        private val profiles: List<String> = listOf("Hiking"),
    ) : LocusBridgeGateway {
        var executions = 0
        var heartRateCalls = 0
        private var currentHeartRate: Int? = null

        override fun readSnapshot(nowMillis: Long): BridgeProtocol.Snapshot = BridgeProtocol.Snapshot(
            state = BridgeProtocol.RecordingState.RECORDING,
            sampledAtEpochSeconds = nowMillis / 1000,
            currentHeartRate = currentHeartRate,
        )

        override fun sendHeartRate(bpm: Int): Boolean {
            heartRateCalls++
            if (heartRateFailures > 0) {
                heartRateFailures--
                throw IllegalStateException("synthetic Locus failure")
            }
            currentHeartRate = bpm
            return true
        }

        override fun recordingProfiles(): List<String> = profiles

        override fun execute(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): BridgeProtocol.Result {
            executions++
            return BridgeProtocol.Result.OK
        }
    }

    private class MemoryStorage : CommandJournal.Storage {
        private var records: List<CommandJournal.Record> = emptyList()
        override fun load(): List<CommandJournal.Record> = records
        override fun save(records: List<CommandJournal.Record>): Boolean {
            this.records = records.map { it.copy() }
            return true
        }
    }

    private class RecordingSender(
        private val yieldDuringSend: Boolean = false,
    ) : PebbleDictionarySender {
        data class Call(val dictionary: PebbleDictionary, val watches: List<WatchIdentifier>)
        val calls = mutableListOf<Call>()

        override suspend fun send(
            dictionary: PebbleDictionary,
            watches: List<WatchIdentifier>,
        ): Map<WatchIdentifier, TransmissionResult> {
            calls += Call(dictionary, watches)
            if (yieldDuringSend) yield()
            return watches.associateWith { TransmissionResult.Success }
        }

        override fun close() = Unit
    }
}
