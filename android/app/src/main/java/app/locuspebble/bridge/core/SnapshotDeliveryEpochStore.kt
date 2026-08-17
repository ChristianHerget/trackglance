package app.locuspebble.bridge.core

import android.annotation.SuppressLint
import android.content.Context
import java.lang.IllegalStateException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durably reserves strictly increasing snapshot epochs before an outbound request may be issued. */
internal class SnapshotDeliveryEpochStore(
    private val storage: Storage,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    internal interface Storage {
        /** Null means no epoch has been reserved yet. */
        fun load(): Long?

        /** True is the durability barrier that authorizes using [epoch] on the wire. */
        fun save(epoch: Long): Boolean
    }

    private val mutex = Mutex()

    suspend fun reserve(observedEpochSeconds: Long): Long? = withContext(ioDispatcher) {
        mutex.withLock {
            runCatching {
                val observed = observedEpochSeconds.coerceIn(0, MAX_EPOCH)
                val previous = storage.load()?.also { stored ->
                    check(stored in 0..MAX_EPOCH) { "Invalid stored snapshot epoch" }
                }
                val next = if (previous == null) {
                    observed
                } else {
                    check(previous < MAX_EPOCH) { "Snapshot epoch space is exhausted" }
                    maxOf(observed, previous + 1L)
                }
                // commit() may update SharedPreferences' in-memory view even when it reports false.
                // No request is emitted on false; a later attempt reloads and reserves again.
                if (!storage.save(next)) return@withLock null
                next
            }.getOrNull()
        }
    }

    companion object {
        private const val MAX_EPOCH = 0xffff_ffffL

        fun sharedPreferences(context: Context): SnapshotDeliveryEpochStore = SnapshotDeliveryEpochStore(
            SharedPreferencesSnapshotDeliveryEpochStorage(context.applicationContext),
        )
    }
}

private class SharedPreferencesSnapshotDeliveryEpochStorage(
    context: Context,
) : SnapshotDeliveryEpochStore.Storage {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Long? {
        if (!preferences.contains(KEY_LAST_RESERVED_EPOCH)) return null
        return preferences.getLong(KEY_LAST_RESERVED_EPOCH, INVALID_EPOCH)
            .takeUnless { it == INVALID_EPOCH }
            ?: throw IllegalStateException("Invalid persisted snapshot epoch")
    }

    // This synchronous return value is the wire-authorization barrier. Never replace with apply().
    @SuppressLint("UseKtx")
    override fun save(epoch: Long): Boolean = preferences.edit()
        .putLong(KEY_LAST_RESERVED_EPOCH, epoch)
        .commit()

    private companion object {
        const val FILE = "snapshot_delivery_epoch"
        const val KEY_LAST_RESERVED_EPOCH = "last_reserved_epoch_v1"
        const val INVALID_EPOCH = -1L
    }
}
