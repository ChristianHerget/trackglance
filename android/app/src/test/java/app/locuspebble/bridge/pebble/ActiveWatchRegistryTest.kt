package app.locuspebble.bridge.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveWatchRegistryTest {
    @Test fun closingOneWatchLeavesTheOtherActive() {
        val registry = ActiveWatchRegistry<String>()
        assertTrue(registry.opened("watch-a"))
        assertTrue(registry.opened("watch-b"))
        assertFalse(registry.opened("watch-a"))
        assertTrue(registry.closed("watch-a"))
        assertEquals(setOf("watch-b"), registry.snapshot())
        assertFalse(registry.isEmpty())
        assertTrue(registry.closed("watch-b"))
        assertTrue(registry.isEmpty())
    }
}
