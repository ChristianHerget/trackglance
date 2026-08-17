package app.locuspebble.bridge.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class PreferencesTest {
    @Test fun wrongTypedRefreshModeFallsBackToAdaptive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("bridge_preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().putInt("refresh_mode", 1).commit()
        try {
            assertEquals(RefreshMode.ADAPTIVE, Preferences.refreshMode(context))
        } finally {
            preferences.edit().clear().commit()
        }
    }

    @Test fun unknownRefreshModeFallsBackToAdaptive() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("bridge_preferences", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("refresh_mode", "REMOVED_MODE").commit()
        try {
            assertEquals(RefreshMode.ADAPTIVE, Preferences.refreshMode(context))
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
