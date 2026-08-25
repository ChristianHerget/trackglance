package io.github.christianherget.trackglance.bridge

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeApplicationTest {
    @Test
    fun processStartupDisablesPebbleKitAutoSelectionBeforeComponentsRun() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        assertTrue(application is BridgeApplication)
        assertFalse(DefaultPebbleAndroidAppPicker.getInstance(application).enableAutoSelect)
    }
}
