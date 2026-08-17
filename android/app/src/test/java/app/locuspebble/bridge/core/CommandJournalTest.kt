package app.locuspebble.bridge.core

import app.locuspebble.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandJournalTest {
    @Test fun completedCommandSurvivesProcessRecreation() {
        val storage = MemoryStorage()
        val key = CommandJournal.Key("watch-a", 10, 1)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.START, "Hiking", null)
        val first = CommandJournal(storage)

        assertTrue(first.begin(key, fingerprint) is CommandJournal.BeginResult.Execute)
        assertTrue(first.complete(key, BridgeProtocol.Result.OK))

        val recreated = CommandJournal(storage)
        assertEquals(
            CommandJournal.BeginResult.Completed(BridgeProtocol.Result.OK),
            recreated.begin(key, fingerprint),
        )
    }

    @Test fun pendingCommandAfterProcessDeathIsNeverExecutedAgain() {
        val storage = MemoryStorage()
        val key = CommandJournal.Key("watch-a", 10, 2)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)
        assertTrue(CommandJournal(storage).begin(key, fingerprint) is CommandJournal.BeginResult.Execute)

        assertEquals(CommandJournal.BeginResult.Pending, CommandJournal(storage).begin(key, fingerprint))
    }

    @Test fun identityIncludesWatchAndFingerprint() {
        val storage = MemoryStorage()
        val journal = CommandJournal(storage)
        val first = CommandJournal.Key("watch-a", 4, 9)
        val sameIdsOtherWatch = CommandJournal.Key("watch-b", 4, 9)
        val start = CommandJournal.fingerprint(BridgeProtocol.Command.START, "Hiking", null)
        val stop = CommandJournal.fingerprint(BridgeProtocol.Command.STOP_SAVE, null, null)

        assertTrue(journal.begin(first, start) is CommandJournal.BeginResult.Execute)
        assertEquals(CommandJournal.BeginResult.Collision, journal.begin(first, stop))
        assertTrue(journal.begin(sameIdsOtherWatch, start) is CommandJournal.BeginResult.Execute)
    }

    @Test fun capacityNeverEvictsAnUncertainPendingCommand() {
        val storage = MemoryStorage()
        val journal = CommandJournal(storage, capacity = 1)
        val pending = CommandJournal.Key("watch", 1, 1)
        val next = CommandJournal.Key("watch", 1, 2)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)

        assertTrue(journal.begin(pending, fingerprint) is CommandJournal.BeginResult.Execute)
        assertEquals(CommandJournal.BeginResult.StorageFailure, journal.begin(next, fingerprint))
        assertEquals(CommandJournal.BeginResult.Pending, journal.begin(pending, fingerprint))
    }

    @Test fun loadingMorePendingCommandsThanCapacityFailsClosedWithoutForgettingThem() {
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)
        val pending = (1L..3L).map { commandId ->
            CommandJournal.Record(
                key = CommandJournal.Key("watch", 1, commandId),
                fingerprint = fingerprint,
                result = null,
                ordinal = commandId,
            )
        }
        val storage = MemoryStorage().also { it.records = pending }
        val journal = CommandJournal(storage, capacity = 2)

        pending.forEach { record ->
            assertEquals(CommandJournal.BeginResult.Pending, journal.begin(record.key, fingerprint))
        }
        assertEquals(
            CommandJournal.BeginResult.StorageFailure,
            journal.begin(CommandJournal.Key("watch", 1, 4), fingerprint),
        )
        assertEquals(pending, journal.snapshot())
    }

    @Test fun failedDurableWriteDoesNotAuthorizeExecutionOrLoseAnEvictedEntry() {
        val storage = MemoryStorage()
        val journal = CommandJournal(storage, capacity = 1)
        val old = CommandJournal.Key("watch", 1, 1)
        val next = CommandJournal.Key("watch", 1, 2)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.STOP_SAVE, null, null)
        assertTrue(journal.begin(old, fingerprint) is CommandJournal.BeginResult.Execute)
        assertTrue(journal.complete(old, BridgeProtocol.Result.OK))

        storage.saveSucceeds = false
        assertEquals(CommandJournal.BeginResult.StorageFailure, journal.begin(next, fingerprint))
        assertEquals(
            CommandJournal.BeginResult.Completed(BridgeProtocol.Result.OK),
            journal.begin(old, fingerprint),
        )
        assertFalse(journal.complete(next, BridgeProtocol.Result.OK))
    }

    @Test fun unreadableDurableStateFailsClosedAfterProcessRestart() {
        val storage = object : CommandJournal.Storage {
            override fun load(): List<CommandJournal.Record> = error("corrupt journal")
            override fun save(records: List<CommandJournal.Record>): Boolean = true
        }
        val journal = CommandJournal(storage)
        val key = CommandJournal.Key("watch", 1, 1)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)

        assertEquals(CommandJournal.BeginResult.StorageFailure, journal.begin(key, fingerprint))
    }

    @Test fun malformedDurableRecordAlsoFailsClosed() {
        val malformed = CommandJournal.Record(
            CommandJournal.Key("watch", 1, 1),
            fingerprint = "not-a-sha-256-fingerprint",
            result = BridgeProtocol.Result.OK,
            ordinal = 1,
        )
        val storage = MemoryStorage().also { it.records = listOf(malformed) }
        val journal = CommandJournal(storage)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.STOP_SAVE, null, null)

        assertEquals(
            CommandJournal.BeginResult.StorageFailure,
            journal.begin(CommandJournal.Key("watch", 2, 1), fingerprint),
        )
    }

    @Test fun malformedWatchIdentifierNeverEntersTheDurableJournal() {
        val storage = MemoryStorage()
        val journal = CommandJournal(storage)
        val fingerprint = CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)

        assertEquals(
            CommandJournal.BeginResult.Collision,
            journal.begin(CommandJournal.Key("broken\ud800", 1, 1), fingerprint),
        )
        assertTrue(storage.records.isEmpty())
        assertTrue(journal.snapshot().isEmpty())
    }

    @Test fun malformedUtf16CommandPayloadsCannotCollapseToReplacementFingerprints() {
        val malformedHigh = CommandJournal.fingerprint(BridgeProtocol.Command.START, "\ud800", null)
        val malformedOther = CommandJournal.fingerprint(BridgeProtocol.Command.START, "\ud801", null)
        val literalQuestion = CommandJournal.fingerprint(BridgeProtocol.Command.START, "?", null)
        val replacementCharacter = CommandJournal.fingerprint(BridgeProtocol.Command.START, "\ufffd", null)

        assertEquals(4, setOf(malformedHigh, malformedOther, literalQuestion, replacementCharacter).size)
    }

    private class MemoryStorage : CommandJournal.Storage {
        var records: List<CommandJournal.Record> = emptyList()
        var saveSucceeds = true

        override fun load(): List<CommandJournal.Record> = records

        override fun save(records: List<CommandJournal.Record>): Boolean {
            if (!saveSucceeds) return false
            this.records = records.map { it.copy() }
            return true
        }
    }
}
