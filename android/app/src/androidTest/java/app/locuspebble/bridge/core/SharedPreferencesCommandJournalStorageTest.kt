package app.locuspebble.bridge.core

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import app.locuspebble.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedPreferencesCommandJournalStorageTest {
    @Test fun corruptPreferenceTypeNeverAuthorizesCommandExecution() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("command_journal", Context.MODE_PRIVATE)
        preferences.edit().clear().putInt("records_v1", 1).commit()
        try {
            val journal = CommandJournal(SharedPreferencesCommandJournalStorage(context))
            val fingerprint = CommandJournal.fingerprint(
                BridgeProtocol.Command.PAUSE_RESUME,
                profileName = null,
                waypointName = null,
            )

            assertEquals(
                CommandJournal.BeginResult.StorageFailure,
                journal.begin(CommandJournal.Key("watch", 1, 1), fingerprint),
            )
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun malformedUtf8WatchIdentifierFailsTheRestoredJournalClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("command_journal", Context.MODE_PRIVATE)
        val fingerprint = CommandJournal.fingerprint(
            BridgeProtocol.Command.PAUSE_RESUME,
            profileName = null,
            waypointName = null,
        )
        val invalidUtf8 = Base64.encodeToString(
            byteArrayOf(0xc3.toByte(), 0x28),
            Base64.NO_WRAP or Base64.URL_SAFE,
        )
        preferences.edit().clear()
            .putStringSet("records_v1", setOf("$invalidUtf8|1|1|$fingerprint|0|1"))
            .commit()
        try {
            val journal = CommandJournal(SharedPreferencesCommandJournalStorage(context))
            assertEquals(
                CommandJournal.BeginResult.StorageFailure,
                journal.begin(CommandJournal.Key("watch", 2, 1), fingerprint),
            )
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun wellFormedUnicodeWatchIdentifierSurvivesProcessRecreation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("command_journal", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        try {
            val storage = SharedPreferencesCommandJournalStorage(context)
            val key = CommandJournal.Key("Pebble 🪨", 3, 7)
            val fingerprint = CommandJournal.fingerprint(
                BridgeProtocol.Command.STOP_SAVE,
                profileName = null,
                waypointName = null,
            )
            val first = CommandJournal(storage)
            assertTrue(first.begin(key, fingerprint) is CommandJournal.BeginResult.Execute)
            assertTrue(first.complete(key, BridgeProtocol.Result.OK))

            assertEquals(
                CommandJournal.BeginResult.Completed(BridgeProtocol.Result.OK),
                CommandJournal(storage).begin(key, fingerprint),
            )
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
