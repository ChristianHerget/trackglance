package app.locuspebble.bridge.pebble

import io.rebble.pebblekit2.common.model.WatchIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticatedIngressTest {
    @Test fun watchEnvelopeCarriesTheBinderAdmissionAcrossPebbleKitAsyncDispatch() {
        val admission = TrustAdmission(0x1234)
        val decoded = AuthenticatedIngressEnvelope.decode(
            WatchIdentifier(AuthenticatedIngressEnvelope.encode("watch-a", admission)),
        )

        assertEquals(WatchIdentifier("watch-a"), decoded?.watch)
        assertEquals(admission, decoded?.admission)
        assertNull(AuthenticatedIngressEnvelope.decode(WatchIdentifier("watch-a")))
        assertNull(
            AuthenticatedIngressEnvelope.decode(
                WatchIdentifier("\u0000locus-pebble-admission:not-a-token\u0000watch-a"),
            ),
        )
    }
}
