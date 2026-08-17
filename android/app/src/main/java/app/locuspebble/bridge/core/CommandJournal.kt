package app.locuspebble.bridge.core

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import app.locuspebble.bridge.protocol.BridgeProtocol
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

private fun strictUtf8Decode(value: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(value))
    .toString()

/**
 * A bounded, durable, at-most-once command journal.
 *
 * A pending entry is committed before Locus is called. If the process dies in the small window
 * between that commit and the Locus broadcast, the retry is failed rather than risking a duplicate
 * pause/resume toggle or waypoint. Completed entries retain the exact result for retransmission.
 */
class CommandJournal(
    private val storage: Storage,
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

    sealed interface Health {
        data object Healthy : Health
        data class Blocked(val message: String) : Health
    }

    interface Storage {
        fun load(): List<Record>
        fun save(records: List<Record>): Boolean
    }

    sealed interface BeginResult {
        data class Execute(val key: Key) : BeginResult
        data class Completed(val result: BridgeProtocol.Result) : BeginResult
        data object Pending : BeginResult
        data object Collision : BeginResult
        data object StorageFailure : BeginResult
    }

    private val records = LinkedHashMap<Key, Record>()
    private var nextOrdinal = 1L
    private var storageReadable = true
    private var blocksNewCommands = false
    private var storageHealth: Health = Health.Healthy

    init {
        val loadedResult = runCatching { storage.load() }
        storageReadable = loadedResult.isSuccess
        if (loadedResult.isFailure) {
            storageHealth = Health.Blocked(UNREADABLE_STORAGE_MESSAGE)
        }
        val decoded = loadedResult.getOrDefault(emptyList())
        val invalid = decoded.any {
            !validKey(it.key) || !validFingerprint(it.fingerprint) || it.ordinal <= 0
        } || decoded.groupingBy(Record::key).eachCount().any { it.value > 1 }
        if (invalid) {
            storageReadable = false
            storageHealth = Health.Blocked(UNREADABLE_STORAGE_MESSAGE)
        }
        val allValid = if (invalid) emptyList() else decoded
            .sortedBy { it.ordinal }
        val pending = allValid.filter { it.result == null }
        val retainedCompleted = allValid.filter { it.result != null }
            .takeLast((capacity - pending.size).coerceAtLeast(0))
        val loaded = (pending + retainedCompleted).sortedBy { it.ordinal }
        loaded.forEach { records[it.key] = it }
        blocksNewCommands = pending.isNotEmpty()
        if (blocksNewCommands && storageReadable) {
            storageHealth = Health.Blocked(UNRESOLVED_PENDING_MESSAGE)
        }
        nextOrdinal = loaded.maxOfOrNull { it.ordinal }?.let { value ->
            if (value == Long.MAX_VALUE) 1L else value + 1L
        } ?: 1L
        if (loaded.any { it.ordinal == Long.MAX_VALUE }) renumber()
    }

    @Synchronized
    fun begin(key: Key, fingerprint: String): BeginResult {
        if (!storageReadable) return BeginResult.StorageFailure
        if (!validKey(key) || !validFingerprint(fingerprint)) {
            return BeginResult.Collision
        }
        records[key]?.let { existing ->
            if (existing.fingerprint != fingerprint) return BeginResult.Collision
            return existing.result?.let(BeginResult::Completed) ?: BeginResult.Pending
        }
        if (blocksNewCommands) return BeginResult.StorageFailure

        val previous = records.toMap()
        val previousOrdinal = nextOrdinal
        while (records.size >= capacity) {
            val removable = records.values.firstOrNull { it.result != null }
                ?: run {
                    storageHealth = Health.Blocked(FULL_STORAGE_MESSAGE)
                    return BeginResult.StorageFailure
                }
            records.remove(removable.key)
        }
        if (nextOrdinal == Long.MAX_VALUE) renumber()
        val record = Record(key, fingerprint, result = null, ordinal = nextOrdinal++)
        records[key] = record
        if (!persist()) {
            records.clear()
            records.putAll(previous)
            nextOrdinal = previousOrdinal
            storageHealth = Health.Blocked(WRITE_STORAGE_MESSAGE)
            return BeginResult.StorageFailure
        }
        storageHealth = Health.Healthy
        return BeginResult.Execute(key)
    }

    @Synchronized
    fun complete(key: Key, result: BridgeProtocol.Result): Boolean {
        val existing = records[key] ?: run {
            storageReadable = false
            storageHealth = Health.Blocked(MISSING_PENDING_MESSAGE)
            return false
        }
        records[key] = existing.copy(result = result)
        if (persist()) {
            storageHealth = Health.Healthy
            return true
        }
        records[key] = existing
        // Locus may already have applied the action. Keep this process and a later process (which
        // will reload the pending record) from accepting any new mutation until explicit recovery.
        storageReadable = false
        storageHealth = Health.Blocked(UNRESOLVED_PENDING_MESSAGE)
        return false
    }

    @Synchronized
    internal fun snapshot(): List<Record> = records.values.toList()

    @Synchronized
    fun health(): Health = storageHealth

    private fun persist(): Boolean = runCatching { storage.save(records.values.toList()) }
        .getOrDefault(false)

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
        private const val UNREADABLE_STORAGE_MESSAGE =
            "Command safety history is unreadable. Commands are blocked to prevent duplicate Locus actions; " +
                "close the watchapp and, after confirming no command is pending, clear this app's storage to recover."
        private const val FULL_STORAGE_MESSAGE =
            "Command safety history is full of unresolved actions. New commands are blocked; " +
                "close the watchapp and, after confirming no command is pending, clear this app's storage to recover."
        private const val WRITE_STORAGE_MESSAGE =
            "Command safety history could not be saved. Commands remain fail-closed; check free storage and retry. " +
                "If this persists, close the watchapp before clearing this app's storage."
        private const val UNRESOLVED_PENDING_MESSAGE =
            "Command safety history contains an unresolved action. New commands are blocked to prevent a duplicate; " +
                "close the watchapp and, after confirming the resulting Locus state, clear this app's storage to recover."
        private const val MISSING_PENDING_MESSAGE =
            "Command safety history lost an in-progress action. Commands remain fail-closed to prevent a duplicate; " +
                "close the watchapp and, after confirming no command is pending, clear this app's storage to recover."
    }
}

class SharedPreferencesCommandJournalStorage(context: Context) : CommandJournal.Storage {
    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): List<CommandJournal.Record> = preferences.getStringSet(KEY_RECORDS, emptySet())
        .orEmpty()
        .map { value -> decode(value) ?: error("Invalid durable command journal entry") }

    // The KTX helper discards commit()'s result; this result is the execution safety barrier.
    @SuppressLint("UseKtx")
    override fun save(records: List<CommandJournal.Record>): Boolean = preferences.edit()
        .putStringSet(KEY_RECORDS, records.map(::encode).toSet())
        .commit()

    private fun encode(record: CommandJournal.Record): String = listOf(
        Base64.encodeToString(strictUtf8Encode(record.key.watchId), BASE64_FLAGS),
        record.key.sessionId.toString(),
        record.key.commandId.toString(),
        record.fingerprint,
        record.result?.wire?.toString() ?: PENDING,
        record.ordinal.toString(),
    ).joinToString(SEPARATOR)

    private fun decode(value: String): CommandJournal.Record? = runCatching {
        val fields = value.split(SEPARATOR)
        if (fields.size != FIELD_COUNT) return null
        val result = if (fields[4] == PENDING) null else {
            val wire = fields[4].toInt()
            BridgeProtocol.Result.entries.firstOrNull {
                it.wire == wire && it.wire <= BridgeProtocol.Result.INVALID_WAYPOINT_NAME.wire
            } ?: return null
        }
        CommandJournal.Record(
            key = CommandJournal.Key(
                watchId = strictUtf8Decode(Base64.decode(fields[0], BASE64_FLAGS)),
                sessionId = fields[1].toLong(),
                commandId = fields[2].toLong(),
            ),
            fingerprint = fields[3],
            result = result,
            ordinal = fields[5].toLong(),
        )
    }.getOrNull()

    private companion object {
        const val FILE = "command_journal"
        const val KEY_RECORDS = "records_v1"
        const val PENDING = "pending"
        const val SEPARATOR = "|"
        const val FIELD_COUNT = 6
        const val BASE64_FLAGS = Base64.NO_WRAP or Base64.URL_SAFE
    }
}
