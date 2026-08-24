package io.github.christianherget.trackglance.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticHistoryTest {
    @Test
    fun coalescesIdenticalFailuresAndKeepsFirstAndLastTimestamps() {
        var now = 100L
        val history = DiagnosticHistory(nowMillis = { now })
        val failure = BridgeFailure(BridgeFailureKind.LOCUS_UNAVAILABLE, "offline")
        history.record(failure)
        now = 250L
        history.record(failure)
        assertEquals(
            DiagnosticEntry(failure, DiagnosticSeverity.WARNING, 100, 250, 2),
            history.entries.value.single(),
        )
    }

    @Test
    fun identicalKindWithDifferentDetailRemainsSeparateAndNewestFirst() {
        var now = 1L
        val history = DiagnosticHistory(nowMillis = { now++ })
        history.record(BridgeFailure.technical("one"))
        history.record(BridgeFailure.technical("two"))
        assertEquals(listOf("two", "one"), history.entries.value.map { it.failure.technicalDetail })
        assertTrue(history.entries.value.all { it.severity == DiagnosticSeverity.ERROR })
    }

    @Test
    fun retainsOnlyTwentyNewestEntriesAndCanBeCleared() {
        val history = DiagnosticHistory()
        repeat(21) { history.record(BridgeFailure.technical("failure-$it")) }
        assertEquals(20, history.entries.value.size)
        assertEquals("failure-20", history.entries.value.first().failure.technicalDetail)
        assertEquals("failure-1", history.entries.value.last().failure.technicalDetail)
        history.clear()
        assertTrue(history.entries.value.isEmpty())
    }

    @Test
    fun expectedUserActionConditionsAreWarnings() {
        assertEquals(
            DiagnosticSeverity.WARNING,
            BridgeFailure(BridgeFailureKind.PEBBLE_COMPANION_NOT_INSTALLED).severity(),
        )
        assertEquals(
            DiagnosticSeverity.ERROR,
            BridgeFailure(BridgeFailureKind.WATCHAPP_INCOMPATIBLE).severity(),
        )
        assertEquals(
            DiagnosticSeverity.ERROR,
            BridgeFailure(BridgeFailureKind.SNAPSHOT_DELIVERY_FAILED).severity(),
        )
    }
}
