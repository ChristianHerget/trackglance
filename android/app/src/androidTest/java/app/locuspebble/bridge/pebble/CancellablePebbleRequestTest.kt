package app.locuspebble.bridge.pebble

import android.os.Bundle
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CancellablePebbleRequestTest {
    @Test fun alreadyDeadEndpointFailsBeforeSubmittingTheRequest() = runBlocking {
        val endpoint = FakeEndpoint(alreadyDead = true)

        assertNull(cancellablePebbleRequest(endpoint, Bundle()))
        assertFalse(endpoint.requested)
    }

    @Test fun binderDeathDuringARequestResumesItAsFailure() = runBlocking {
        val endpoint = FakeEndpoint()
        val response = async(start = CoroutineStart.UNDISPATCHED) {
            cancellablePebbleRequest(endpoint, Bundle())
        }
        assertTrue(endpoint.requested)

        endpoint.die()

        assertNull(response.await())
        assertEquals(1, endpoint.closedRegistrations)
    }

    @Test fun deathDuringRegistrationNeverStartsTheRemoteRequest() = runBlocking {
        val endpoint = FakeEndpoint(dieDuringRegistration = true)

        assertNull(cancellablePebbleRequest(endpoint, Bundle()))
        assertFalse(endpoint.requested)
        assertEquals(1, endpoint.closedRegistrations)
    }

    @Test fun cancellationDuringRegistrationNeverStartsTheRemoteRequest() = runBlocking {
        val registrationStarted = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val endpoint = FakeEndpoint(
            registrationStarted = registrationStarted,
            releaseRegistration = releaseRegistration,
        )
        val request = async(Dispatchers.Default) {
            cancellablePebbleRequest(endpoint, Bundle())
        }
        assertTrue(registrationStarted.await(2, TimeUnit.SECONDS))

        request.cancel()
        releaseRegistration.countDown()
        request.cancelAndJoin()

        assertFalse(endpoint.requested)
        assertEquals(1, endpoint.closedRegistrations)
    }

    @Test fun neverCallbackRequestCancelsCleanlyAndALaterRequestProgresses() = runBlocking {
        val endpoint = FakeEndpoint()

        assertNull(withTimeoutOrNull(25) { cancellablePebbleRequest(endpoint, Bundle()) })
        assertEquals(1, endpoint.closedRegistrations)

        endpoint.respondImmediately = true
        val response = cancellablePebbleRequest(endpoint, Bundle())
        assertEquals("ok", response?.getString("result"))
        assertEquals(2, endpoint.closedRegistrations)
    }

    @Test fun callbackFromAnotherThreadMayCompleteBeforeRequestReturns() = runBlocking {
        val endpoint = FakeEndpoint(respondFromAnotherThreadBeforeReturn = true)

        val response = cancellablePebbleRequest(endpoint, Bundle())

        assertEquals("ok", response?.getString("result"))
        assertTrue(endpoint.callbackCompletedBeforeRequestReturned)
        assertEquals(1, endpoint.closedRegistrations)
    }

    private class FakeEndpoint(
        private val alreadyDead: Boolean = false,
        private val dieDuringRegistration: Boolean = false,
        private val registrationStarted: CountDownLatch? = null,
        private val releaseRegistration: CountDownLatch? = null,
        private val respondFromAnotherThreadBeforeReturn: Boolean = false,
    ) : PebbleRequestEndpoint {
        var requested = false
        var respondImmediately = false
        var closedRegistrations = 0
        var callbackCompletedBeforeRequestReturned = false
        private var deathRecipient: (() -> Unit)? = null

        override fun registerDeathRecipient(onDeath: () -> Unit): AutoCloseable? {
            if (alreadyDead) return null
            registrationStarted?.countDown()
            releaseRegistration?.await(2, TimeUnit.SECONDS)
            deathRecipient = onDeath
            val registration = AutoCloseable {
                closedRegistrations++
                deathRecipient = null
            }
            if (dieDuringRegistration) onDeath()
            return registration
        }

        override fun request(payload: Bundle, callback: (Bundle) -> Unit): Boolean {
            requested = true
            val response = Bundle().apply { putString("result", "ok") }
            if (respondFromAnotherThreadBeforeReturn) {
                val callbackFinished = CountDownLatch(1)
                Thread {
                    callback(response)
                    callbackFinished.countDown()
                }.start()
                callbackCompletedBeforeRequestReturned = callbackFinished.await(2, TimeUnit.SECONDS)
            } else if (respondImmediately) {
                callback(response)
            }
            return true
        }

        fun die() {
            val recipient = deathRecipient
            recipient?.invoke()
        }
    }
}
