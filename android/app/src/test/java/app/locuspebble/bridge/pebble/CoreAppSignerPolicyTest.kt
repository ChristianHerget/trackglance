package app.locuspebble.bridge.pebble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreAppSignerPolicyTest {
    @Test fun verifiedSigningHistoryPermitsAnApprovedKeyRotation() {
        val approved = "10".repeat(32)
        val rotated = "11".repeat(32)

        val status = CoreAppSignerPolicy.evaluate(
            InstalledPackageSigners(
                current = setOf(rotated),
                history = setOf(approved, rotated),
            ),
            approvedSignerDigest = approved,
        )

        assertEquals(CoreAppTrustKind.USER_APPROVED, status.kind)
    }

    @Test fun unrelatedReinstallDoesNotInheritApprovalForTheSamePackageName() {
        val approved = "10".repeat(32)
        val replacement = "11".repeat(32)

        val status = CoreAppSignerPolicy.evaluate(
            InstalledPackageSigners(current = setOf(replacement), history = setOf(replacement)),
            approvedSignerDigest = approved,
        )

        assertEquals(CoreAppTrustKind.APPROVAL_REQUIRED, status.kind)
        assertFalse(status.trusted)
        assertEquals(replacement, status.enrollmentCandidate)
    }

    @Test fun arbitraryDebugSignerRequiresExactExplicitApproval() {
        val debug = "ab".repeat(32)
        val signers = InstalledPackageSigners(current = setOf(debug), history = setOf(debug))

        val unapproved = CoreAppSignerPolicy.evaluate(signers, approvedSignerDigest = null)
        val wrong = CoreAppSignerPolicy.evaluate(signers, approvedSignerDigest = "cd".repeat(32))
        val approved = CoreAppSignerPolicy.evaluate(signers, approvedSignerDigest = debug.uppercase())

        assertEquals(CoreAppTrustKind.APPROVAL_REQUIRED, unapproved.kind)
        assertEquals(debug, unapproved.enrollmentCandidate)
        assertFalse(wrong.trusted)
        assertEquals(CoreAppTrustKind.USER_APPROVED, approved.kind)
        assertTrue(approved.trusted)
    }

    @Test fun enrollmentRevalidatesTheCurrentInstalledDigestBeforeSaving() {
        val displayed = "12".repeat(32)
        val replacement = "34".repeat(32)
        val storage = MemoryApprovalStorage()
        var current = displayed
        val source = object : CoreAppSignerSource {
            override fun installedSigners() = InstalledPackageSigners(setOf(current), setOf(current))
        }
        val repository = CoreAppTrustRepository(source, storage)

        assertEquals(displayed, repository.inspect().enrollmentCandidate)
        current = replacement
        assertFalse(repository.approveCurrentSigner(displayed))
        assertNull(storage.digest)
        assertTrue(repository.approveCurrentSigner(replacement))
        assertEquals(replacement, storage.digest)
    }

    @Test fun multipleCurrentSignersCannotBeEnrolledThroughTheSingleDigestUi() {
        val first = "12".repeat(32)
        val second = "34".repeat(32)
        val signers = InstalledPackageSigners(setOf(first, second), setOf(first, second))
        val storage = MemoryApprovalStorage()
        val repository = CoreAppTrustRepository(
            signerSource = object : CoreAppSignerSource {
                override fun installedSigners() = signers
            },
            approvalStorage = storage,
        )

        assertNull(CoreAppSignerPolicy.evaluate(signers, null).enrollmentCandidate)
        assertFalse(repository.approveCurrentSigner(first))
        assertNull(storage.digest)
    }

    @Test fun failedCommitCannotAuthorizeItsProcessVisiblePreferenceValue() {
        val candidate = "56".repeat(32)
        var processVisibleValue: String? = null
        var commitSucceeds = false
        val storage = CommitConfirmedCoreAppSignerApprovalStorage(initialDigest = { null }) { value ->
            // SharedPreferences mutates its in-memory map before commit() reports disk failure.
            processVisibleValue = value
            commitSucceeds
        }

        assertFalse(storage.save(candidate))
        assertEquals(candidate, processVisibleValue)
        assertNull(storage.load())

        commitSucceeds = true
        assertTrue(storage.save(candidate))
        assertEquals(candidate, storage.load())
    }

    @Test fun failedReplacementOrRevocationFailsClosedUntilASuccessfulRetry() {
        val original = "12".repeat(32)
        val replacement = "34".repeat(32)
        var processVisibleValue: String? = original
        var commitSucceeds = false
        val storage = CommitConfirmedCoreAppSignerApprovalStorage(initialDigest = { original }) { value ->
            processVisibleValue = value
            commitSucceeds
        }

        assertFalse(storage.save(replacement))
        assertEquals(replacement, processVisibleValue)
        assertNull(storage.load())

        assertFalse(storage.save(null))
        assertNull(processVisibleValue)
        assertNull(storage.load())

        commitSucceeds = true
        assertTrue(storage.save(replacement))
        assertEquals(replacement, storage.load())
    }

    @Test fun constructionDoesNotReadAuthorizationStorageOnTheCallingThread() {
        val approved = "78".repeat(32)
        var reads = 0
        val storage = CommitConfirmedCoreAppSignerApprovalStorage(
            initialDigest = {
                reads++
                approved
            },
            commitValue = { true },
        )

        assertEquals(0, reads)
        assertEquals(approved, storage.load())
        assertEquals(1, reads)
        assertEquals(approved, storage.load())
        assertEquals(1, reads)
    }

    private class MemoryApprovalStorage : CoreAppSignerApprovalStorage {
        var digest: String? = null
        override fun load(): String? = digest
        override fun save(digest: String?): Boolean {
            this.digest = digest
            return true
        }
    }
}
