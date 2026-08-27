package io.github.christianherget.trackglance.bridge.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepPersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun stepPreferencesReplaceLegacyKeyPerIdentityEntriesWithOneCurrentRecord() {
        val preferences = context.getSharedPreferences(STEP_FILE, Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("legacy-a", "old")
            .putString("legacy-b", "old")
            .commit()
        try {
            val key = StepAccumulator.Key("watch", 3, 123_456L)
            val accumulator = StepAccumulator(StepAccumulator.PreferencesStorage(context))
            assertTrue(accumulator.accept(key, 7, 0, 12))
            assertEquals(setOf("current"), preferences.all.keys)
            assertEquals(
                12,
                StepAccumulator(StepAccumulator.PreferencesStorage(context)).steps(key),
            )

            val replacement = key.copy(recordingStartMillis = 123_457L)
            assertTrue(accumulator.accept(replacement, 7, 1, 2))
            assertEquals(setOf("current"), preferences.all.keys)
            val recreated = StepAccumulator(StepAccumulator.PreferencesStorage(context))
            assertEquals(2, recreated.steps(replacement))
            assertEquals(null, recreated.steps(key))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test
    fun sessionPreferencesCommitOneCurrentAuthorityRecord() {
        val preferences = context.getSharedPreferences(SESSION_FILE, Context.MODE_PRIVATE)
        preferences
            .edit()
            .clear()
            .putString("legacy-a", "old")
            .putString("legacy-b", "old")
            .commit()
        try {
            val first = WatchSessionAuthority.Key("watch", 3)
            val authority = WatchSessionAuthority(WatchSessionAuthority.PreferencesStorage(context))
            assertTrue(authority.establish(first, 7))
            assertEquals(setOf("current"), preferences.all.keys)
            val second = WatchSessionAuthority.Key("other", 4)
            assertTrue(authority.establish(second, 8))
            assertEquals(setOf("current"), preferences.all.keys)
            val recreated = WatchSessionAuthority(WatchSessionAuthority.PreferencesStorage(context))
            assertTrue(recreated.isCurrent(second, 8))
            assertFalse(recreated.isCurrent(first, 7))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    private companion object {
        const val STEP_FILE = "watch_step_accumulator"
        const val SESSION_FILE = "watch_session_authority"
    }
}
