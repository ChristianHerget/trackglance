package io.github.christianherget.trackglance.bridge.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol

/** Bounded, best-effort accumulation of watch-provided recording step deltas. */
class StepAccumulator(private val storage: Storage = MemoryStorage()) {
    data class Key(val watchId: String, val trustGeneration: Long, val recordingStartMillis: Long)

    data class State(
        val sessionId: Long,
        val sequence: Long,
        val steps: Int,
        val available: Boolean,
    )

    data class Record(val key: Key, val state: State)

    private var loaded = false
    private var current: Record? = null

    @Synchronized
    @Suppress("ReturnCount")
    fun accept(key: Key, sessionId: Long, sequence: Long, delta: Int): Boolean {
        val validDelta = delta >= 0 || delta == BridgeProtocol.UNAVAILABLE
        if (sessionId !in UNSIGNED_RANGE || sequence !in UNSIGNED_RANGE || !validDelta) {
            return false
        }
        val previous = currentRecord()?.takeIf { it.key == key }?.state
        if (previous != null) {
            if (previous.sessionId == sessionId && sequence <= previous.sequence) return false
            // Session authority is owned by BridgeRuntime. The accumulator only accepts a new
            // authorized stream at its sequence-zero boundary.
            if (previous.sessionId != sessionId && sequence != 0L) return false
        }
        val total = (previous?.steps ?: 0).toLong() + delta.coerceAtLeast(0)
        if (total > Int.MAX_VALUE) return false
        val next =
            Record(
                key,
                State(
                    sessionId = sessionId,
                    sequence = sequence,
                    steps = total.toInt(),
                    available = delta != BridgeProtocol.UNAVAILABLE,
                ),
            )
        // PreferencesStorage schedules apply() before this method ACKs. The cache is authoritative
        // for the live process; sudden process death may lose only the unflushed tail.
        if (!storage.write(next)) return false
        current = next
        return true
    }

    @Synchronized
    fun steps(key: Key): Int? =
        currentRecord()?.takeIf { it.key == key }?.state?.takeIf(State::available)?.steps

    private fun currentRecord(): Record? {
        if (!loaded) {
            current = storage.read()
            loaded = true
        }
        return current
    }

    interface Storage {
        fun read(): Record?

        /** Returns after persistence has either been scheduled or rejected. */
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
                val recordingStartMillis = fields[2].toLongOrNull() ?: return null
                val sessionId =
                    fields[3].toLongOrNull()?.takeIf { it in UNSIGNED_RANGE } ?: return null
                val sequence =
                    fields[4].toLongOrNull()?.takeIf { it in UNSIGNED_RANGE } ?: return null
                val steps = fields[5].toIntOrNull()?.takeIf { it >= 0 } ?: return null
                val available = fields[6].toBooleanStrictOrNull() ?: return null
                Record(
                    Key(watchId, trustGeneration, recordingStartMillis),
                    State(sessionId, sequence, steps, available),
                )
            }

        @SuppressLint("UseKtx")
        override fun write(record: Record): Boolean {
            val key = record.key
            val state = record.state
            preferences
                .edit()
                .clear()
                .putString(
                    CURRENT_RECORD,
                    listOf(
                            encodeWatchId(key.watchId),
                            key.trustGeneration,
                            key.recordingStartMillis,
                            state.sessionId,
                            state.sequence,
                            state.steps,
                            state.available,
                        )
                        .joinToString("|"),
                )
                .apply()
            return true
        }

        private companion object {
            const val FILE = "watch_step_accumulator"
            const val CURRENT_RECORD = "current"
            const val RECORD_FIELDS = 7
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
