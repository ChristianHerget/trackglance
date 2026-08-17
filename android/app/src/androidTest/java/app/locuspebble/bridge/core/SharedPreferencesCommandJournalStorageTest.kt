package app.locuspebble.bridge.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.locuspebble.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
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
}
