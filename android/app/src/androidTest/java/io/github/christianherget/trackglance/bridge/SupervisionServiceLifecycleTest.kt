package io.github.christianherget.trackglance.bridge

import android.app.NotificationManager
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SupervisionServiceLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
    fun enabledServiceSurvivesHiddenActivityAndOffStopsIt() {
        val notifications = compose.activity.getSystemService(NotificationManager::class.java)
        compose.onNodeWithTag("supervision-AUTO_START").performScrollTo().performClick()
        compose.waitUntil(5000) {
            notifications.activeNotifications.any { it.id == SupervisionNotifications.STATUS_ID }
        }
        try {
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            assertTrue(
                notifications.activeNotifications.any {
                    it.id == SupervisionNotifications.STATUS_ID
                }
            )
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("supervision-OFF").performScrollTo().performClick()
            compose.waitUntil(5000) {
                notifications.activeNotifications.none {
                    it.id == SupervisionNotifications.STATUS_ID
                }
            }
        } finally {
            compose.activityRule.scenario.onActivity {
                SupervisionManager.get(it)
                    .configure(
                        io.github.christianherget.trackglance.bridge.core.SupervisionSettings()
                    )
            }
        }
    }
}
