package io.github.christianherget.trackglance.bridge.core

import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

private fun strictUtf8Encode(value: String): ByteArray {
    val bytes = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .encode(CharBuffer.wrap(value))
    val result = ByteArray(bytes.remaining())
    bytes.get(result)
    return result
}

/**
 * A bounded, in-memory, at-most-once command journal.
 *
 * It prevents duplicate Locus actions from button presses during the same bridge session.
 */
class CommandJournal(
    private val capacity: Int = 128,
) {
    init {
        require(capacity > 0)
    }

    data class Key(val watchId: String, val sessionId: Long, val commandId: Long)

    data class Record(
        val key: Key,
        val fingerprint: String,
        val result: BridgeProtocol.Result?,
        val ordinal: Long,
    )

    sealed interface BeginResult {
        data class Execute(val key: Key) : BeginResult
        data class Completed(val result: BridgeProtocol.Result) : BeginResult
        data object Pending : BeginResult
        data object Collision : BeginResult
    }

    private val records = LinkedHashMap<Key, Record>()
    private var nextOrdinal = 1L

    @Synchronized
    fun begin(key: Key, fingerprint: String): BeginResult {
        if (!validKey(key) || !validFingerprint(fingerprint)) {
            return BeginResult.Collision
        }
        records[key]?.let { existing ->
            if (existing.fingerprint != fingerprint) return BeginResult.Collision
            return existing.result?.let(BeginResult::Completed) ?: BeginResult.Pending
        }

        while (records.size >= capacity) {
            val removable = records.values.firstOrNull { it.result != null }
                ?: records.values.firstOrNull() // if all are pending, evict eldest
                ?: break
            records.remove(removable.key)
        }
        if (nextOrdinal == Long.MAX_VALUE) renumber()
        val record = Record(key, fingerprint, result = null, ordinal = nextOrdinal++)
        records[key] = record
        return BeginResult.Execute(key)
    }

    @Synchronized
    fun complete(key: Key, result: BridgeProtocol.Result): Boolean {
        val existing = records[key] ?: return false
        records[key] = existing.copy(result = result)
        return true
    }

    @Synchronized
    internal fun snapshot(): List<Record> = records.values.toList()

    private fun renumber() {
        val normalized = records.values.sortedBy { it.ordinal }.mapIndexed { index, record ->
            record.copy(ordinal = index + 1L)
        }
        records.clear()
        normalized.forEach { records[it.key] = it }
        nextOrdinal = normalized.size + 1L
    }

    companion object {
        fun fingerprint(
            command: BridgeProtocol.Command,
            profileName: String?,
            waypointName: String?,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(command.wire).array())
            listOf(profileName, waypointName).forEach { value ->
                if (value == null) {
                    digest.update(0.toByte())
                } else {
                    val bytes = runCatching { strictUtf8Encode(value) }.getOrNull()
                    if (bytes != null) {
                        digest.update(1.toByte())
                        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
                        digest.update(bytes)
                    } else {
                        // Preserve exact malformed UTF-16 code units instead of encoder replacement bytes.
                        digest.update(2.toByte())
                        digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.length).array())
                        value.forEach { codeUnit ->
                            digest.update((codeUnit.code ushr 8).toByte())
                            digest.update(codeUnit.code.toByte())
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun validKey(key: Key): Boolean = BridgeProtocol.validWatchId(key.watchId) &&
            validUnsigned(key.sessionId) && validUnsigned(key.commandId)

        private fun validFingerprint(value: String): Boolean = value.length == SHA_256_HEX_LENGTH &&
            value.all { it in '0'..'9' || it in 'a'..'f' }

        private fun validUnsigned(value: Long): Boolean = value in 0..UInt.MAX_VALUE.toLong()
        private const val SHA_256_HEX_LENGTH = 64
    }
}
