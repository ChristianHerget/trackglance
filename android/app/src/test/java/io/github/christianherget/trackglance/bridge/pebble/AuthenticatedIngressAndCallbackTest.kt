package io.github.christianherget.trackglance.bridge.pebble

import io.github.christianherget.trackglance.bridge.core.BoundedAbandonableCallExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedIngressAndCallbackTest {
    @Test fun aHungTwoWayCoreCallbackCannotBlockRevocationOrGrowAQueue() {
        val executor = BoundedAbandonableCallExecutor(1, "callback-test")
        val delivery = BoundedCallbackDelivery(executor)
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        var authorized = true
        try {
            assertTrue(
                delivery.deliver(
                    stillAuthorized = { authorized },
                    callback = {
                        callbackStarted.countDown()
                        releaseCallback.await()
                    },
                ),
            )
            assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))

            // This is the trust transition: it is independent of the abandoned callback worker.
            authorized = false
            assertFalse(
                delivery.deliver(
                    stillAuthorized = { authorized },
                    callback = { error("A saturated executor must not queue this callback") },
                ),
            )
            assertFalse(authorized)
        } finally {
            releaseCallback.countDown()
            executor.close()
        }
    }
}
