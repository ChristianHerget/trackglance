package app.locuspebble.bridge.pebble

import android.os.Bundle
import app.locuspebble.bridge.core.BoundedAbandonableCallExecutor
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
        assertTrue(endpoint.awaitRequested())

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
        assertTrue(endpoint.awaitClosedRegistration())
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
        assertTrue(endpoint.awaitRequestReturned())
        assertTrue(endpoint.callbackCompletedBeforeRequestReturned)
        assertEquals(1, endpoint.closedRegistrations)
    }

    @Test fun synchronousBlockedProxyIsAbandonedSoRevocationAndLaterRequestProgress() = runBlocking {
        val workers = BoundedAbandonableCallExecutor(2, "blocked-proxy-test")
        val leases = SerializedCoreTrustLeases()
        val blocked = CountDownLatch(1)
        val releaseBlocked = CountDownLatch(1)
        val endpoint = FakeEndpoint(
            blockRequest = {
                blocked.countDown()
                releaseBlocked.await()
            },
        ).apply { respondImmediately = true }
        try {
            val firstSend = async(start = CoroutineStart.UNDISPATCHED) {
                leases.withOutbound {
                    withTimeoutOrNull(50) {
                        cancellablePebbleRequest(endpoint, Bundle(), workers)
                    }
                }
            }
            assertTrue(blocked.await(2, TimeUnit.SECONDS))
            assertNull(firstSend.await())

            var revoked = false
            leases.mutateTrust { revoked = true }
            assertTrue(revoked)

            val healthy = FakeEndpoint().apply { respondImmediately = true }
            val recovered = cancellablePebbleRequest(healthy, Bundle(), workers)
            assertEquals("ok", recovered?.getString("result"))
        } finally {
            releaseBlocked.countDown()
            workers.close()
        }
    }

    private class FakeEndpoint(
        private val alreadyDead: Boolean = false,
        private val dieDuringRegistration: Boolean = false,
        private val registrationStarted: CountDownLatch? = null,
        private val releaseRegistration: CountDownLatch? = null,
        private val respondFromAnotherThreadBeforeReturn: Boolean = false,
        private val blockRequest: (() -> Unit)? = null,
    ) : PebbleRequestEndpoint {
        @Volatile var requested = false
        var respondImmediately = false
        var closedRegistrations = 0
        var callbackCompletedBeforeRequestReturned = false
        private var deathRecipient: (() -> Unit)? = null
        private val requestStarted = CountDownLatch(1)
        private val requestReturned = CountDownLatch(1)
        private val registrationClosed = CountDownLatch(1)

        override fun registerDeathRecipient(onDeath: () -> Unit): AutoCloseable? {
            if (alreadyDead) return null
            registrationStarted?.countDown()
            releaseRegistration?.await(2, TimeUnit.SECONDS)
            deathRecipient = onDeath
            val registration = AutoCloseable {
                closedRegistrations++
                deathRecipient = null
                registrationClosed.countDown()
            }
            if (dieDuringRegistration) onDeath()
            return registration
        }

        override fun request(payload: Bundle, callback: (Bundle) -> Unit): Boolean {
            requested = true
            requestStarted.countDown()
            blockRequest?.invoke()
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
            requestReturned.countDown()
            return true
        }

        fun die() {
            val recipient = deathRecipient
            recipient?.invoke()
        }

        fun awaitRequested(): Boolean = requestStarted.await(2, TimeUnit.SECONDS)

        fun awaitRequestReturned(): Boolean = requestReturned.await(2, TimeUnit.SECONDS)

        fun awaitClosedRegistration(): Boolean = registrationClosed.await(2, TimeUnit.SECONDS)
    }
}
