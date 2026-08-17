package app.locuspebble.bridge

import android.app.Application
import app.locuspebble.bridge.pebble.TrustedPebbleCompanionProvider

/** Disables picker auto-selection synchronously, then establishes trusted Core selection asynchronously. */
class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustedPebbleCompanionProvider.disableAutoSelection(this)
        TrustedPebbleCompanionProvider.initializeAsync(this)
    }
}
