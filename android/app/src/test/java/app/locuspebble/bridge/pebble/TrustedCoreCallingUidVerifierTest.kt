package app.locuspebble.bridge.pebble

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustedCoreCallingUidVerifierTest {
    @Test fun requiresCoreUidPackageMappingAndSignerAtTheSameBinderEntry() {
        var packages = setOf(TRUSTED_CORE_APP_PACKAGE)
        var applicationUid = 42
        var signerTrusted = true
        val verifier = TrustedCoreCallingUidVerifier(
            packagesForUid = { packages },
            coreApplicationUid = { applicationUid },
            signerTrusted = { signerTrusted },
        )

        assertTrue(verifier.isTrusted(42))
        packages = setOf("attacker.example")
        assertFalse(verifier.isTrusted(42))
        packages = setOf(TRUSTED_CORE_APP_PACKAGE)
        applicationUid = 43
        assertFalse(verifier.isTrusted(42))
        applicationUid = 42
        signerTrusted = false
        assertFalse(verifier.isTrusted(42))
    }

    @Test fun lookupFailuresFailClosed() {
        val verifier = TrustedCoreCallingUidVerifier(
            packagesForUid = { error("Package manager unavailable") },
            coreApplicationUid = { 42 },
            signerTrusted = { true },
        )

        assertFalse(verifier.isTrusted(42))
    }
}
