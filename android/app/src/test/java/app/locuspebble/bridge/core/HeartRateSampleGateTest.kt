package app.locuspebble.bridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateSampleGateTest {
    @Test fun validatesRangeFreshnessAndIncreasingSequencePerSession() {
        val gate = HeartRateSampleGate()
        assertTrue(gate.accept(10, 1, 123, 1_000, 1_000))
        assertFalse(gate.accept(10, 1, 124, 1_000, 1_000))
        assertFalse(gate.accept(10, 0, 124, 1_000, 1_000))
        assertFalse(gate.accept(10, 2, 24, 1_000, 1_000))
        assertFalse(gate.accept(10, 2, 251, 1_000, 1_000))
        assertFalse(gate.accept(10, 2, 123, 969, 1_000))
        assertFalse(gate.accept(10, 2, 123, 1_006, 1_000))
        assertTrue(gate.accept(11, 0, 123, 1_000, 1_000))
    }
}
