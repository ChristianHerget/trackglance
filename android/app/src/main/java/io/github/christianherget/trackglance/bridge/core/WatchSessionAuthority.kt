package io.github.christianherget.trackglance.bridge.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64

/** Bounded durable authority for the current process session of one admitted watch. */
class WatchSessionAuthority(private val storage: Storage = MemoryStorage()) {
    data class Key(val watchId: String, val trustGeneration: Long)

    data class Record(val key: Key, val sessionId: Long)

    private var loaded = false
    private var current: Record? = null

    @Synchronized
    @Suppress("ReturnCount")
    fun establish(key: Key, sessionId: Long): Boolean {
        if (sessionId !in UNSIGNED_RANGE) return false
        val next = Record(key, sessionId)
        if (!storage.write(next)) return false
        // Session authority changes only after commit() established durable state.
        current = next
        loaded = true
        return true
    }

    @Synchronized
    fun establishIfMissing(key: Key, sessionId: Long): Boolean {
        val present = currentRecord()?.takeIf { it.key == key }
        return if (present == null) establish(key, sessionId) else present.sessionId == sessionId
    }

    @Synchronized
    fun isCurrent(key: Key, sessionId: Long): Boolean =
        sessionId in UNSIGNED_RANGE &&
            currentRecord()?.let { it.key == key && it.sessionId == sessionId } == true

    private fun currentRecord(): Record? {
        if (!loaded) {
            current = storage.read()
            loaded = true
        }
        return current
    }

    interface Storage {
        fun read(): Record?

        /** Must return true only after the record is durable. */
        fun write(record: Record): Boolean
    }

    class MemoryStorage : Storage {
        private var current: Record? = null

        override fun read() = current

        override fun write(record: Record): Boolean {
            current = record
            return true
        }
    }

    class PreferencesStorage(context: Context) : Storage {
        private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        override fun read(): Record? =
            preferences.getString(CURRENT_RECORD, null)?.split('|')?.let { fields ->
                if (fields.size != RECORD_FIELDS) return null
                val watchId = decodeWatchId(fields[0]) ?: return null
                val trustGeneration = fields[1].toLongOrNull() ?: return null
                val sessionId =
                    fields[2].toLongOrNull()?.takeIf { it in UNSIGNED_RANGE } ?: return null
                Record(Key(watchId, trustGeneration), sessionId)
            }

        @SuppressLint("UseKtx")
        override fun write(record: Record): Boolean =
            preferences
                .edit()
                .clear()
                .putString(
                    CURRENT_RECORD,
                    listOf(
                            encodeWatchId(record.key.watchId),
                            record.key.trustGeneration,
                            record.sessionId,
                        )
                        .joinToString("|"),
                )
                .commit()

        private companion object {
            const val FILE = "watch_session_authority"
            const val CURRENT_RECORD = "current"
            const val RECORD_FIELDS = 3
        }
    }

    private companion object {
        val UNSIGNED_RANGE = 0L..0xffff_ffffL
        const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE

        fun encodeWatchId(watchId: String): String =
            Base64.encodeToString(watchId.toByteArray(Charsets.UTF_8), BASE64_FLAGS)

        fun decodeWatchId(encoded: String): String? = runCatching {
            String(Base64.decode(encoded, BASE64_FLAGS), Charsets.UTF_8)
        }
            .getOrNull()
    }
}
