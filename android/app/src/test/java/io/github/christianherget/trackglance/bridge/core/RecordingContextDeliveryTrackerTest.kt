package io.github.christianherget.trackglance.bridge.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingContextDeliveryTrackerTest {
    @Test
    fun acknowledgedIdentitySuppressesOnlyTheSameProfileAndTarget() {
        val tracker = RecordingContextDeliveryTracker<String>()
        val attempt = requireNotNull(tracker.begin("watch-a", "profile-1"))

        assertTrue(tracker.commit(attempt))
        assertNull(tracker.begin("watch-a", "profile-1"))
        assertNotNull(tracker.begin("watch-a", "profile-2"))
        assertNotNull(tracker.begin("watch-b", "profile-1"))
    }

    @Test
    fun invalidationPreventsLateSuccessFromRestoringAcknowledgement() {
        val tracker = RecordingContextDeliveryTracker<String>()
        val attempt = requireNotNull(tracker.begin("watch", "profile"))

        tracker.invalidate("watch")

        assertFalse(tracker.commit(attempt))
        assertNotNull(tracker.begin("watch", "profile"))
    }

    @Test
    fun failedAttemptRemainsPending() {
        val tracker = RecordingContextDeliveryTracker<String>()

        assertNotNull(tracker.begin("watch", "profile"))
        assertNotNull(tracker.begin("watch", "profile"))
    }

    @Test
    fun exactInvalidationDoesNotCancelAnotherTargetsAttempt() {
        val tracker = RecordingContextDeliveryTracker<String>()
        val other = requireNotNull(tracker.begin("watch-b", "profile"))

        tracker.invalidate("watch-a")

        assertTrue(tracker.commit(other))
        assertNull(tracker.begin("watch-b", "profile"))
    }

    @Test
    fun globalInvalidationCancelsEveryInFlightAttempt() {
        val tracker = RecordingContextDeliveryTracker<String>()
        val first = requireNotNull(tracker.begin("watch-a", "profile-a"))
        val second = requireNotNull(tracker.begin("watch-b", "profile-b"))

        tracker.invalidateAll()

        assertFalse(tracker.commit(first))
        assertFalse(tracker.commit(second))
        assertNotNull(tracker.begin("watch-a", "profile-a"))
        assertNotNull(tracker.begin("watch-b", "profile-b"))
    }
}
