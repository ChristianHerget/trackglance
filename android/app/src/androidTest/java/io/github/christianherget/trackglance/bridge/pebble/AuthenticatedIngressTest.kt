package io.github.christianherget.trackglance.bridge.pebble

import io.rebble.pebblekit2.common.model.WatchIdentifier
import org.junit.Assert.assertEquals
import org.junit.Test

class AuthenticatedIngressTest {
    @Test
    fun typedIngressKeepsAdmissionSeparateFromTheWatchIdentifier() {
        val admission = TrustAdmission(0x1234)
        val ingress = AuthenticatedWatchIngress(WatchIdentifier("watch-a"), admission)

        assertEquals("watch-a", ingress.watch.value)
        assertEquals(admission, ingress.admission)
    }
}
