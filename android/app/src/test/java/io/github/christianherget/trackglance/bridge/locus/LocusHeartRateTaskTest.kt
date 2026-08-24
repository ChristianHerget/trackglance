package io.github.christianherget.trackglance.bridge.locus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocusHeartRateTaskTest {
    @Test fun emitsExactDataTaskPayload() {
        assertEquals("com.asamm.locus.DATA_TASK", LocusHeartRateTask.ACTION)
        assertEquals("tasks", LocusHeartRateTask.EXTRA_TASKS)
        assertEquals("{heart_rate:{data:123.0}}", LocusHeartRateTask.payload(123))
        assertThrows(IllegalArgumentException::class.java) { LocusHeartRateTask.payload(24) }
        assertThrows(IllegalArgumentException::class.java) { LocusHeartRateTask.payload(251) }
    }
}
