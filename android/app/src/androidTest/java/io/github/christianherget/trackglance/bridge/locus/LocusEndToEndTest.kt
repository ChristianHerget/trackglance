package io.github.christianherget.trackglance.bridge.locus

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, non-mutating contract checks against a real Locus Map installation. */
@LargeTest
@RunWith(AndroidJUnit4::class)
class LocusEndToEndTest {
    private lateinit var gateway: LocusGateway

    @Before
    fun requireExplicitOptInAndIdleLocus() {
        assumeTrue(
            "Real Locus test was not explicitly enabled",
            InstrumentationRegistry.getArguments().getString("runLocusIntegration") == "true",
        )
        gateway = LocusGateway(ApplicationProvider.getApplicationContext())
        assertEquals(
            "The non-mutating contract test requires idle Locus",
            BridgeProtocol.RecordingState.STOPPED,
            gateway.readSnapshot().state,
        )
    }

    @Test
    fun catalogHasNumericIdentityAndObsoleteStartIsRejected() {
        val profiles = (gateway.recordingProfiles() as? RecordingProfilesResult.Success)?.profiles
        assertTrue("Locus has no recording profile", !profiles.isNullOrEmpty())
        assertTrue(profiles!!.all { BridgeProtocol.validLocusProfileId(it.id) })
        assertTrue(profiles.all { BridgeProtocol.validLocusProfileName(it.name) })

        assertEquals(
            BridgeProtocol.Result.INVALID_STATE,
            gateway.execute(BridgeProtocol.Command.START, profiles.first().name),
        )
        assertEquals(BridgeProtocol.RecordingState.STOPPED, gateway.readSnapshot().state)
    }
}
