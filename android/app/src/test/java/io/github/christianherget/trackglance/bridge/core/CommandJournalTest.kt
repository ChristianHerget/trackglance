package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandJournalTest {
    @Test
    fun identityIncludesWatchAndFingerprint() {
        val journal = CommandJournal()
        val first = CommandJournal.Key("watch-a", 4, 9)
        val sameIdsOtherWatch = CommandJournal.Key("watch-b", 4, 9)
        val start = CommandJournal.fingerprint(BridgeProtocol.Command.START, "Hiking", null)
        val stop = CommandJournal.fingerprint(BridgeProtocol.Command.STOP_SAVE, null, null)

        assertTrue(journal.begin(first, start) is CommandJournal.BeginResult.Execute)
        assertEquals(CommandJournal.BeginResult.Collision, journal.begin(first, stop))
        assertTrue(journal.begin(sameIdsOtherWatch, start) is CommandJournal.BeginResult.Execute)
    }

    @Test
    fun capacityEvictsOldestCommand() {
        val journal = CommandJournal(capacity = 1)
        val old = CommandJournal.Key("watch", 1, 1)
        val next = CommandJournal.Key("watch", 1, 2)
        val fingerprint =
            CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)

        assertTrue(journal.begin(old, fingerprint) is CommandJournal.BeginResult.Execute)
        assertTrue(journal.begin(next, fingerprint) is CommandJournal.BeginResult.Execute)
        assertEquals(CommandJournal.BeginResult.Pending, journal.begin(next, fingerprint))
        assertEquals(1, journal.snapshot().size)
        assertEquals(next, journal.snapshot().first().key)
    }

    @Test
    fun malformedWatchIdentifierNeverEntersTheJournal() {
        val journal = CommandJournal()
        val fingerprint =
            CommandJournal.fingerprint(BridgeProtocol.Command.PAUSE_RESUME, null, null)

        assertEquals(
            CommandJournal.BeginResult.Collision,
            journal.begin(CommandJournal.Key("broken\ud800", 1, 1), fingerprint),
        )
        assertTrue(journal.snapshot().isEmpty())
    }

    @Test
    fun malformedUtf16CommandPayloadsCannotCollapseToReplacementFingerprints() {
        val malformedHigh = CommandJournal.fingerprint(BridgeProtocol.Command.START, "\ud800", null)
        val malformedOther =
            CommandJournal.fingerprint(BridgeProtocol.Command.START, "\ud801", null)
        val literalQuestion = CommandJournal.fingerprint(BridgeProtocol.Command.START, "?", null)
        val replacementCharacter =
            CommandJournal.fingerprint(BridgeProtocol.Command.START, "\ufffd", null)

        assertEquals(
            4,
            setOf(malformedHigh, malformedOther, literalQuestion, replacementCharacter).size,
        )
    }
}
