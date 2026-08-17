package app.locuspebble.bridge.core

import android.annotation.SuppressLint
import android.content.Context
import app.locuspebble.bridge.protocol.BridgeProtocol
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Dedicated durable sender floor that advances exactly once per authorized profile transfer. */
internal class ProfileTransferSerialStore(
    private val storage: Storage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal interface Storage {
        /** Null means no transfer has ever been authorized by this installation. */
        fun load(): Long?

        /** True is the durability barrier that authorizes emitting this serial's chunk 0. */
        fun save(serial: Long): Boolean
    }

    private val mutex = Mutex()

    suspend fun reserve(): Int? = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                val previous = storage.load()?.also { stored ->
                    check(stored in 0..BridgeProtocol.TRANSFER_SERIAL_MASK) {
                        "Invalid stored profile transfer serial"
                    }
                }
                val next = if (previous == null) {
                    0L
                } else {
                    (previous + 1L) and BridgeProtocol.TRANSFER_SERIAL_MASK
                }
                // A failed commit may still change SharedPreferences' process-local map. No frame
                // is authorized; the next reservation safely observes it and leaves another gap.
                if (!storage.save(next)) return@withLock null
                next.toInt()
            }.getOrNull()
        }
    }

    companion object {
        fun sharedPreferences(context: Context): ProfileTransferSerialStore =
            ProfileTransferSerialStore(
                SharedPreferencesProfileTransferSerialStorage(context.applicationContext),
            )
    }
}

private class SharedPreferencesProfileTransferSerialStorage(
    context: Context,
) : ProfileTransferSerialStore.Storage {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Long? {
        if (!preferences.contains(KEY_LAST_RESERVED_SERIAL)) return null
        return preferences.getLong(KEY_LAST_RESERVED_SERIAL, INVALID_SERIAL)
            .takeUnless { it == INVALID_SERIAL }
            ?: error("Invalid persisted profile transfer serial")
    }

    @SuppressLint("UseKtx")
    override fun save(serial: Long): Boolean = preferences.edit()
        .putLong(KEY_LAST_RESERVED_SERIAL, serial)
        .commit()

    private companion object {
        const val FILE = "profile_transfer_serial"
        const val KEY_LAST_RESERVED_SERIAL = "last_reserved_serial_v1"
        const val INVALID_SERIAL = -1L
    }
}
