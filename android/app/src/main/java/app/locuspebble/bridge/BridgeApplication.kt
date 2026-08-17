package app.locuspebble.bridge

import android.app.Application
import app.locuspebble.bridge.pebble.TrustedPebbleCompanionProvider

/** Establishes the CoreApp-only PebbleKit selection before any app component can bind. */
class BridgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TrustedPebbleCompanionProvider.get(this).initializeBlocking()
    }
}
