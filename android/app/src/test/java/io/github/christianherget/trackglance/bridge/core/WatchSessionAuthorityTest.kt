package io.github.christianherget.trackglance.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSessionAuthorityTest {
    private val key = WatchSessionAuthority.Key("watch", 4)

    @Test
    fun multipleOlderSessionsCannotReplaceCurrentAuthority() {
        val authority = WatchSessionAuthority()
        assertTrue(authority.establish(key, 1))
        assertTrue(authority.establish(key, 2))
        assertTrue(authority.establish(key, 3))
        assertFalse(authority.isCurrent(key, 1))
        assertFalse(authority.isCurrent(key, 2))
        assertTrue(authority.isCurrent(key, 3))
        assertFalse(authority.establishIfMissing(key, 1))
        assertTrue(authority.isCurrent(key, 3))
    }

    @Test
    fun authoritySurvivesProcessRecreationAndReplacesTheSingleRecord() {
        val storage = WatchSessionAuthority.MemoryStorage()
        assertTrue(WatchSessionAuthority(storage).establish(key, 27))
        val replacement = key.copy(watchId = "other", trustGeneration = 5)
        assertTrue(WatchSessionAuthority(storage).establish(replacement, 31))
        assertEquals(replacement, storage.read()?.key)
        val recreated = WatchSessionAuthority(storage)
        assertTrue(recreated.establishIfMissing(replacement, 31))
        assertFalse(recreated.isCurrent(key, 27))
    }

    @Test
    fun lazyCacheIsReusedAfterOneDurableRead() {
        val storage = CountingStorage(WatchSessionAuthority.Record(key, 27))
        val authority = WatchSessionAuthority(storage)
        assertTrue(authority.isCurrent(key, 27))
        assertTrue(authority.isCurrent(key, 27))
        assertEquals(1, storage.reads)
    }

    @Test
    fun failedCommitDoesNotChangeCachedAuthority() {
        val storage = CountingStorage(WatchSessionAuthority.Record(key, 27), fail = true)
        val authority = WatchSessionAuthority(storage)
        assertTrue(authority.isCurrent(key, 27))
        assertFalse(authority.establish(key, 31))
        assertTrue(authority.isCurrent(key, 27))
        assertFalse(authority.isCurrent(key, 31))
    }

    private class CountingStorage(
        private var current: WatchSessionAuthority.Record?,
        var fail: Boolean = false,
    ) : WatchSessionAuthority.Storage {
        var reads = 0

        override fun read(): WatchSessionAuthority.Record? {
            reads++
            return current
        }

        override fun write(record: WatchSessionAuthority.Record): Boolean {
            if (fail) return false
            current = record
            return true
        }
    }
}
