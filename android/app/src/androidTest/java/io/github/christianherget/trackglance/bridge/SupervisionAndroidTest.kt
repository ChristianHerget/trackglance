package io.github.christianherget.trackglance.bridge

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import io.github.christianherget.trackglance.bridge.core.SupervisionMode
import io.github.christianherget.trackglance.bridge.core.SupervisionRecording
import io.github.christianherget.trackglance.bridge.core.SupervisionSettings
import io.github.christianherget.trackglance.bridge.core.SupervisionSource
import io.github.christianherget.trackglance.bridge.core.SupervisionStatus
import io.github.christianherget.trackglance.bridge.protocol.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SupervisionAndroidTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun grantNotificationsForDeliveryChecks() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val instrumentation =
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.grantRuntimePermission(
                instrumentation.targetContext.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
            )
        }
    }

    @Test
    fun controlsExposeSelectionAndDisableDelayWhileOff() {
        val state = mutableStateOf(SupervisionStatus())
        compose.setContent {
            TrackGlanceTheme {
                SupervisionControls(
                    state.value,
                    { state.value = state.value.copy(settings = it) },
                    {},
                )
            }
        }
        compose.onNodeWithTag("supervision-OFF").assertIsSelected()
        compose.onNodeWithTag("supervision-delay").assertIsNotEnabled()
        compose.onNodeWithTag("supervision-AUTO_START").performClick().assertIsSelected()
        compose.runOnIdle { assertEquals(SupervisionMode.AUTO_START, state.value.settings.mode) }
    }

    @Test
    fun recreationRestoresOnlySettingsAndDefaultsRemainOffThirty() {
        main {
            context
                .getSharedPreferences("watchapp_supervision", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
            val original = SupervisionManager(context)
            assertEquals(SupervisionSettings(), original.machine.settings)
            original.configure(SupervisionSettings(SupervisionMode.AUTO_START, 75))
            original.machine.observe(SupervisionRecording.RECORDING, 123)
            original.machine.learn(SupervisionSource.STEPS)
            val recreated = SupervisionManager(context)
            assertEquals(
                SupervisionSettings(SupervisionMode.AUTO_START, 75),
                recreated.machine.settings,
            )
            assertTrue(recreated.machine.sources.isEmpty())
            original.configure(SupervisionSettings())
        }
    }

    @Test
    fun deniedNotificationsDisableNotifyButRetainAutomaticRecovery() {
        assertEquals(
            SupervisionMode.OFF,
            SupervisionSettings(SupervisionMode.NOTIFY).withNotificationAvailability(false).mode,
        )
        assertEquals(
            SupervisionMode.AUTO_START,
            SupervisionSettings(SupervisionMode.AUTO_START)
                .withNotificationAvailability(false)
                .mode,
        )
        assertEquals(
            SupervisionMode.NOTIFY,
            SupervisionSettings(SupervisionMode.NOTIFY).withNotificationAvailability(true).mode,
        )
    }

    @Test
    fun separateChannelsAndExplicitImmutableNotificationActions() {
        val notifications = SupervisionNotifications(context)
        notifications.createChannels()
        val system = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            system.getNotificationChannel(SupervisionNotifications.STATUS_CHANNEL).importance,
        )
        assertEquals(
            NotificationManager.IMPORTANCE_DEFAULT,
            system.getNotificationChannel(SupervisionNotifications.ALERT_CHANNEL).importance,
        )
        assertTrue(
            system.getNotificationChannel(SupervisionNotifications.ALERT_CHANNEL).shouldVibrate()
        )
        notifications.alert(setOf(SupervisionSource.STEPS), 987)
        try {
            compose.waitUntil(timeoutMillis = 5000) {
                system.activeNotifications.any { it.id == SupervisionNotifications.ALERT_ID }
            }
            val alert =
                system.activeNotifications
                    .single { it.id == SupervisionNotifications.ALERT_ID }
                    .notification
            assertNotNull(alert.contentIntent)
            assertNotNull(alert.deleteIntent)
            assertEquals(1, alert.actions.size)
            assertEquals(context.getString(R.string.supervision_start), alert.actions[0].title)
        } finally {
            notifications.clear()
        }
    }

    @Test
    fun staleActionsCannotDismissOrLaunchForNewOutage() {
        main {
            val manager = SupervisionManager.get(context)
            manager.configure(SupervisionSettings(SupervisionMode.AUTO_START))
            manager.machine.observe(SupervisionRecording.RECORDING, 123)
            manager.machine.learn(SupervisionSource.STEPS)
            manager.machine.opened()
            manager.machine.explicitlyClosed()
            val old = manager.machine.outageId
            manager.machine.opened()
            manager.machine.explicitlyClosed()
            val receiver = SupervisionActionReceiver()
            receiver.onReceive(context, Intent().setAction("start").putExtra("outage", old))
            receiver.onReceive(context, Intent().setAction("dismiss").putExtra("outage", old))
            assertFalse(manager.manualRequested)
            assertFalse(manager.machine.dismissed)
            manager.configure(SupervisionSettings())
        }
    }

    @Test
    fun delayedObservationCannotClearNewerOutage() {
        main {
            val manager = SupervisionManager(context)
            manager.configure(SupervisionSettings(SupervisionMode.AUTO_START))
            manager.machine.observe(SupervisionRecording.RECORDING, 123)
            manager.machine.learn(SupervisionSource.STEPS)
            val token = manager.observationToken()
            manager.machine.opened()
            manager.machine.explicitlyClosed()
            val outage = manager.machine.outageId
            manager.observed(
                BridgeProtocol.Snapshot(
                    state = BridgeProtocol.RecordingState.STOPPED,
                    sampledAtEpochSeconds = 1,
                ),
                observationToken = token,
            )
            assertEquals(outage, manager.machine.outageId)
            assertEquals(setOf(SupervisionSource.STEPS), manager.machine.sources)
            manager.configure(SupervisionSettings())
        }
    }

    private fun main(block: () -> Unit) =
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
