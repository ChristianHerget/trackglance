package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepAccumulatorTest {
    private val key = StepAccumulator.Key("watch", 3, 123_456L)

    @Test
    fun sumsDeltasAndRejectsReplayWithinSession() {
        val accumulator = StepAccumulator()
        assertTrue(accumulator.accept(key, 7, 0, 12))
        assertFalse(accumulator.accept(key, 7, 0, 99))
        assertTrue(accumulator.accept(key, 7, 1, 8))
        assertEquals(20, accumulator.steps(key))
    }

    @Test
    fun reopenedWatchSessionMayRestartSequenceWithoutLosingAccumulation() {
        val accumulator = StepAccumulator()
        assertTrue(accumulator.accept(key, 7, 42, 12))
        assertFalse(accumulator.accept(key, 8, 1, 3))
        assertTrue(accumulator.accept(key, 8, 0, 3))
        assertEquals(15, accumulator.steps(key))
    }

    @Test
    fun recordingWatchAndTrustIdentityPartitionTheSingleCurrentTotal() {
        val storage = StepAccumulator.MemoryStorage()
        val accumulator = StepAccumulator(storage)
        accumulator.accept(key, 7, 0, 12)
        val next = key.copy(recordingStartMillis = 123_457L)
        assertNull(accumulator.steps(next))
        assertTrue(accumulator.accept(next, 7, 4, 2))
        assertEquals(next, storage.read()?.key)
        assertNull(accumulator.steps(key))
        assertNull(accumulator.steps(next.copy(watchId = "other")))
        assertNull(accumulator.steps(next.copy(trustGeneration = 4)))
    }

    @Test
    fun unavailableAndRecoveryAdvanceSequenceWithoutLosingThePrefix() {
        val accumulator = StepAccumulator()
        accumulator.accept(key, 7, 0, 12)
        assertTrue(accumulator.accept(key, 7, 1, BridgeProtocol.UNAVAILABLE))
        assertNull(accumulator.steps(key))
        assertFalse(accumulator.accept(key, 7, 1, 3))
        assertTrue(accumulator.accept(key, 7, 2, 0))
        assertEquals(12, accumulator.steps(key))
    }

    @Test
    fun unavailableSourceDoesNotInventAZeroTotal() {
        val accumulator = StepAccumulator()
        assertTrue(accumulator.accept(key, 7, 0, BridgeProtocol.UNAVAILABLE))
        assertNull(accumulator.steps(key))
    }

    @Test
    fun overflowIsRejectedWithoutAdvancingSequenceOrChangingTotal() {
        val accumulator = StepAccumulator()
        assertTrue(accumulator.accept(key, 7, 0, Int.MAX_VALUE))
        assertFalse(accumulator.accept(key, 7, 1, 1))
        assertEquals(Int.MAX_VALUE, accumulator.steps(key))
        assertTrue(accumulator.accept(key, 7, 1, 0))
    }

    @Test
    fun lazyCacheIsReusedAfterOneStorageRead() {
        val storage = CountingStorage()
        val accumulator = StepAccumulator(storage)
        assertNull(accumulator.steps(key))
        assertNull(accumulator.steps(key))
        assertEquals(1, storage.reads)
        assertTrue(accumulator.accept(key, 7, 0, 3))
        assertEquals(3, accumulator.steps(key))
        assertEquals(1, storage.reads)
    }

    @Test
    fun rejectedPersistenceScheduleDoesNotAdvanceTheCache() {
        val storage = FailingStorage()
        val accumulator = StepAccumulator(storage)
        assertFalse(accumulator.accept(key, 7, 0, 3))
        assertNull(accumulator.steps(key))
        storage.fail = false
        assertTrue(accumulator.accept(key, 7, 0, 3))
        assertEquals(3, accumulator.steps(key))
    }

    @Test
    fun suddenDeathMayLoseOnlyTheUnflushedTail() {
        val storage = AsynchronousStorage()
        val firstProcess = StepAccumulator(storage)
        assertTrue(firstProcess.accept(key, 7, 0, 3))
        assertEquals(3, firstProcess.steps(key))
        assertNull(StepAccumulator(storage).steps(key))

        storage.flush()
        val secondProcess = StepAccumulator(storage)
        assertEquals(3, secondProcess.steps(key))
        assertTrue(secondProcess.accept(key, 7, 1, 2))
        assertEquals(5, secondProcess.steps(key))
        assertEquals(3, StepAccumulator(storage).steps(key))
    }

    private open class CountingStorage : StepAccumulator.Storage {
        var reads = 0
        protected var current: StepAccumulator.Record? = null

        override fun read(): StepAccumulator.Record? {
            reads++
            return current
        }

        override fun write(record: StepAccumulator.Record): Boolean {
            current = record
            return true
        }
    }

    private class FailingStorage : CountingStorage() {
        var fail = true

        override fun write(record: StepAccumulator.Record): Boolean {
            if (fail) return false
            return super.write(record)
        }
    }

    private class AsynchronousStorage : StepAccumulator.Storage {
        private var durable: StepAccumulator.Record? = null
        private var scheduled: StepAccumulator.Record? = null

        override fun read() = durable

        override fun write(record: StepAccumulator.Record): Boolean {
            scheduled = record
            return true
        }

        fun flush() {
            durable = scheduled
        }
    }
}
