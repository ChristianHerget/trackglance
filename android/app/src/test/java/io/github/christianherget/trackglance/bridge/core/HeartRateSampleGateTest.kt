package io.github.christianherget.trackglance.bridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateSampleGateTest {
    @Test
    fun validatesRangeFreshnessAndIncreasingSequencePerSession() {
        val gate = HeartRateSampleGate()
        assertTrue(gate.accept("watch-a", 10, 1, 123, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", 10, 1, 124, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", 10, 0, 124, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", 10, 2, 24, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", 10, 2, 251, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", 10, 2, 123, 969, 1_000))
        assertFalse(gate.accept("watch-a", 10, 2, 123, 1_006, 1_000))
        assertTrue(gate.accept("watch-a", 11, 0, 123, 1_000, 1_000))
        assertTrue(gate.accept("watch-b", 10, 1, 123, 1_000, 1_000))
        assertFalse(gate.accept("", 12, 1, 123, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", -1, 1, 123, 1_000, 1_000))
        assertFalse(gate.accept("watch-a", 1, 0x1_0000_0000L, 123, 1_000, 1_000))
    }

    @Test
    fun returningToAnEarlierSessionDoesNotReopenItsReplayWindow() {
        val gate = HeartRateSampleGate()
        assertTrue(gate.accept("watch", 100, 7, 120, 1_000, 1_000))
        assertTrue(gate.accept("watch", 101, 1, 121, 1_000, 1_000))
        assertFalse(gate.accept("watch", 100, 7, 120, 1_000, 1_000))
        assertTrue(gate.accept("watch", 100, 8, 122, 1_000, 1_000))
    }

    @Test
    fun oldSignerSequenceCannotPoisonTheReapprovedSignersStream() {
        val gate = HeartRateSampleGate()
        assertTrue(gate.accept("watch", 100, 99, 120, 1_000, 1_000, trustGeneration = 1))
        assertTrue(gate.accept("watch", 100, 1, 121, 1_000, 1_000, trustGeneration = 2))
        assertFalse(gate.accept("watch", 100, 99, 120, 1_000, 1_000, trustGeneration = 1))
    }
}
