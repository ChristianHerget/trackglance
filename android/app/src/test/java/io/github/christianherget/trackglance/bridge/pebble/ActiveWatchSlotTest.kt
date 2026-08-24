package io.github.christianherget.trackglance.bridge.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWatchSlotTest {
    @Test fun openingAnotherWatchReplacesTheActiveWatch() {
        val registry = ActiveWatchSlot<String>()
        assertTrue(registry.opened("watch-a"))
        assertTrue(registry.opened("watch-b"))
        assertEquals(setOf("watch-b"), registry.snapshot())
        assertFalse(registry.closed("watch-a"))
        assertFalse(registry.isEmpty())
        assertTrue(registry.closed("watch-b"))
        assertTrue(registry.isEmpty())
    }

    @Test fun observationRecoversAnEmptySlotButCannotDisplaceAnOpenWatch() {
        val registry = ActiveWatchSlot<String>()
        assertTrue(registry.observed("watch-a"))
        assertFalse(registry.observed("watch-b"))
        assertEquals(setOf("watch-a"), registry.snapshot())
    }

    @Test fun selectionLossClearsTheActiveWatch() {
        val registry = ActiveWatchSlot<String>()
        registry.opened("watch-a")

        assertTrue(registry.clear())
        assertTrue(registry.isEmpty())
        assertFalse(registry.clear())
        assertTrue(registry.opened("watch-a"))
    }
}
