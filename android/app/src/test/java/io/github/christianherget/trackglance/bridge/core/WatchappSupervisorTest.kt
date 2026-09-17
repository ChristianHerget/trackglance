package io.github.christianherget.trackglance.bridge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchappSupervisorTest {
    private var time = 0L
    private val supervisor = WatchappSupervisor { time }

    private fun activate(mode: SupervisionMode = SupervisionMode.AUTO_START, delay: Int = 30) {
        supervisor.configure(SupervisionSettings(mode, delay))
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        supervisor.learn(SupervisionSource.HEART_RATE)
        supervisor.opened()
        supervisor.explicitlyClosed()
    }

    @Test
    fun defaultsAndProcessRecreationHaveNoExpectation() {
        assertEquals(SupervisionSettings(), supervisor.settings)
        activate()
        val recreated = WatchappSupervisor { time }
        recreated.configure(supervisor.settings)
        assertEquals(SupervisionPhase.WAITING, recreated.status().phase)
        assertTrue(recreated.sources.isEmpty())
    }

    @Test
    fun bothSourcesQualifyIndependentlyAndTogether() {
        for (source in SupervisionSource.entries) {
            supervisor.configure(SupervisionSettings(SupervisionMode.OFF))
            supervisor.configure(SupervisionSettings(SupervisionMode.NOTIFY))
            supervisor.observe(SupervisionRecording.RECORDING, 123)
            supervisor.learn(source)
            assertEquals(setOf(source), supervisor.sources)
        }
        supervisor.learn(SupervisionSource.HEART_RATE)
        assertEquals(SupervisionSource.entries.toSet(), supervisor.sources)
    }

    @Test
    fun offAndUnknownIdentityCannotLearn() {
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        supervisor.learn(SupervisionSource.STEPS)
        assertTrue(supervisor.sources.isEmpty())
        supervisor.configure(SupervisionSettings(SupervisionMode.NOTIFY))
        supervisor.observe(SupervisionRecording.RECORDING, 0)
        supervisor.learn(SupervisionSource.STEPS)
        assertTrue(supervisor.sources.isEmpty())
    }

    @Test
    fun everyDelayStartsAtExplicitCloseAndDuplicatesDoNotExtendIt() {
        for (delay in 15..90 step 15) {
            supervisor.opened()
            activate(SupervisionMode.NOTIFY, delay)
            val deadline = time + delay * 1000L
            time = deadline - 1
            supervisor.explicitlyClosed()
            supervisor.tick()
            assertFalse(supervisor.alert)
            time++
            supervisor.tick()
            assertTrue(supervisor.alert)
            assertNull(supervisor.deadline)
        }
    }

    @Test
    fun noPacketMeansNoOutage() {
        supervisor.configure(SupervisionSettings(SupervisionMode.AUTO_START))
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        supervisor.explicitlyClosed()
        time = 100000
        assertNull(supervisor.beginLaunch())
        supervisor.tick()
        assertFalse(supervisor.alert)
    }

    @Test
    fun dispatchRequiresOpenConfirmationAndOnlyThreeAttempts() {
        activate()
        for (attempt in 1..3) {
            time = attempt * 30000L
            val token = supervisor.beginLaunch()!!
            assertEquals(attempt, supervisor.attempts)
            supervisor.launchResult(token, true)
            time += 4999
            supervisor.tick()
            assertEquals(attempt > 1, supervisor.alert)
            time++
            supervisor.tick()
            assertTrue(supervisor.alert)
        }
        time += 90000
        assertNull(supervisor.beginLaunch())
        assertNull(supervisor.deadline)
    }

    @Test
    fun confirmedOpenCancelsAndLaterCloseGetsFreshBudget() {
        activate()
        time = 30000
        val token = supervisor.beginLaunch()!!
        supervisor.launchResult(token, true)
        supervisor.opened()
        time = 35000
        supervisor.tick()
        assertFalse(supervisor.alert)
        assertNull(supervisor.deadline)
        supervisor.explicitlyClosed()
        assertEquals(65000L, supervisor.deadline)
        assertEquals(0, supervisor.attempts)
    }

    @Test
    fun explicitFailureAlertsImmediatelyAndDismissalDoesNotStopRetries() {
        activate()
        time = 30000
        supervisor.launchResult(supervisor.beginLaunch()!!, false)
        assertTrue(supervisor.alert)
        supervisor.dismiss(supervisor.outageId)
        time = 60000
        supervisor.launchResult(supervisor.beginLaunch()!!, false)
        assertFalse(supervisor.alert)
        assertEquals(2, supervisor.attempts)
    }

    @Test
    fun manualAttemptsAreSerializedAndDoNotConsumeAutomaticBudget() {
        activate()
        val token = supervisor.beginLaunch(manual = true)!!
        assertNull(supervisor.beginLaunch(manual = true))
        assertEquals(0, supervisor.attempts)
        supervisor.launchResult(token, true)
        assertNull(supervisor.beginLaunch(manual = true))
        time = 5000
        supervisor.tick()
        time = 30000
        assertNotNull(supervisor.beginLaunch())
        assertEquals(1, supervisor.attempts)
    }

    @Test
    fun pauseClearsAlertAndResumeRestartsFullDelay() {
        activate()
        time = 30000
        supervisor.launchResult(supervisor.beginLaunch()!!, false)
        supervisor.observe(SupervisionRecording.PAUSED, 123)
        assertFalse(supervisor.alert)
        assertNull(supervisor.deadline)
        assertTrue(supervisor.closed)
        assertTrue(supervisor.sources.isNotEmpty())
        time = 100000
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        assertEquals(130000L, supervisor.deadline)
        assertEquals(0, supervisor.attempts)
    }

    @Test
    fun unavailableRetainsAlertAndBudgetAndRestartsDelay() {
        activate()
        time = 30000
        supervisor.launchResult(supervisor.beginLaunch()!!, false)
        supervisor.observe(SupervisionRecording.UNKNOWN, null)
        assertTrue(supervisor.alert)
        assertNull(supervisor.deadline)
        assertNull(supervisor.beginLaunch(manual = true))
        time = 100000
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        assertEquals(130000L, supervisor.deadline)
        assertEquals(1, supervisor.attempts)
    }

    @Test
    fun editsPreserveBudgetDismissalAndAlertHistory() {
        activate()
        time = 30000
        supervisor.launchResult(supervisor.beginLaunch()!!, false)
        val outage = supervisor.outageId
        supervisor.configure(SupervisionSettings(SupervisionMode.AUTO_START, 90))
        supervisor.dismiss(outage)
        assertEquals(120000L, supervisor.deadline)
        assertEquals(1, supervisor.attempts)
        assertFalse(supervisor.alert)
        time = 120000
        supervisor.launchResult(supervisor.beginLaunch()!!, false)
        assertFalse(supervisor.alert)
    }

    @Test
    fun obsoleteResultsCannotAffectNewOutage() {
        activate()
        time = 30000
        val token = supervisor.beginLaunch()!!
        supervisor.opened()
        supervisor.explicitlyClosed()
        supervisor.launchResult(token, false)
        assertFalse(supervisor.alert)
        assertEquals(60000L, supervisor.deadline)
    }

    @Test
    fun stopReplacementAndOffClearLearningAndNotifications() {
        for (end in 0..2) {
            activate()
            time += 30000
            supervisor.launchResult(supervisor.beginLaunch()!!, false)
            when (end) {
                0 -> supervisor.observe(SupervisionRecording.STOPPED, null)
                1 -> supervisor.observe(SupervisionRecording.RECORDING, 456)
                else -> supervisor.configure(SupervisionSettings())
            }
            assertTrue(supervisor.sources.isEmpty())
            assertFalse(supervisor.alert)
            assertNull(supervisor.deadline)
        }
    }

    @Test
    fun thirdUnconfirmedAttemptSurvivesUnavailableWithoutFourthLaunch() {
        activate()
        for (attempt in 1..2) {
            time = attempt * 30000L
            supervisor.launchResult(supervisor.beginLaunch()!!, false)
        }
        time = 90000
        val token = supervisor.beginLaunch()!!
        supervisor.observe(SupervisionRecording.UNKNOWN, null)
        supervisor.launchResult(token, true)
        time = 100000
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        assertEquals(130000L, supervisor.deadline)
        time = 130000
        supervisor.tick()
        assertNull(supervisor.beginLaunch())
        assertEquals(3, supervisor.attempts)
        assertNull(supervisor.deadline)
        assertNotNull(supervisor.beginLaunch(manual = true))
        assertEquals(3, supervisor.attempts)
    }

    @Test
    fun editsInvalidateResultsButKeepAttemptsAlreadyMade() {
        activate()
        time = 30000
        val old = supervisor.beginLaunch()!!
        supervisor.configure(SupervisionSettings(SupervisionMode.AUTO_START, 15))
        supervisor.launchResult(old, false)
        assertFalse(supervisor.alert)
        assertEquals(1, supervisor.attempts)
        assertEquals(45000L, supervisor.deadline)
    }

    @Test
    fun pausedRecordingCanLearnSourcesWithoutStartingAnOutage() {
        supervisor.configure(SupervisionSettings(SupervisionMode.AUTO_START))
        supervisor.observe(SupervisionRecording.PAUSED, 123)
        supervisor.learn(SupervisionSource.STEPS)
        supervisor.learn(SupervisionSource.HEART_RATE)
        supervisor.explicitlyClosed()
        assertEquals(SupervisionSource.entries.toSet(), supervisor.sources)
        assertNull(supervisor.deadline)
        supervisor.observe(SupervisionRecording.RECORDING, 123)
        assertEquals(30000L, supervisor.deadline)
    }
}
