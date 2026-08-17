package app.locuspebble.bridge.pebble

import android.content.Context
import app.locuspebble.bridge.core.BoundedTargetDelivery
import app.locuspebble.bridge.protocol.BridgeProtocol
import io.rebble.pebblekit2.client.DefaultPebbleSender
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import kotlinx.coroutines.delay

interface PebbleDictionarySender : AutoCloseable {
    suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>?
}

class DefaultPebbleDictionarySender(context: Context) : PebbleDictionarySender {
    private val delegate = DefaultPebbleSender(context)

    override suspend fun send(
        dictionary: PebbleDictionary,
        watches: List<WatchIdentifier>,
    ): Map<WatchIdentifier, TransmissionResult>? = delegate.sendDataToPebble(
        BridgeProtocol.APP_UUID,
        dictionary,
        watches,
    )

    override fun close() = delegate.close()
}

/** Performs a small bounded retry and verifies every requested watch result. */
class ReliablePebbleTransport(
    private val sender: PebbleDictionarySender,
    private val maxAttempts: Int = 3,
    private val retryDelay: suspend (attempt: Int) -> Unit = { attempt -> delay(100L * attempt) },
) : AutoCloseable {
    private val delivery = BoundedTargetDelivery<WatchIdentifier>(maxAttempts, retryDelay)

    suspend fun send(dictionary: PebbleDictionary, watches: Collection<WatchIdentifier>): Boolean {
        return delivery.deliver(watches) { targets ->
            sender.send(dictionary, targets).orEmpty()
                .filterValues { it == TransmissionResult.Success }
                .keys
        }
    }

    override fun close() = sender.close()
}

/** Thread-safe tracking for the watches whose copy of this watchapp is currently open. */
class ActiveWatchRegistry<T> {
    private val values = LinkedHashSet<T>()

    @Synchronized fun opened(value: T): Boolean = values.add(value)
    @Synchronized fun closed(value: T): Boolean = values.remove(value)
    @Synchronized fun snapshot(): Set<T> = values.toSet()
    @Synchronized fun isEmpty(): Boolean = values.isEmpty()
}
