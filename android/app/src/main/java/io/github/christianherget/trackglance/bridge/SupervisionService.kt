package io.github.christianherget.trackglance.bridge

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import io.github.christianherget.trackglance.bridge.core.SupervisionMode
import io.github.christianherget.trackglance.bridge.core.SupervisionRecording
import io.github.christianherget.trackglance.bridge.core.SupervisionSettings
import io.github.christianherget.trackglance.bridge.locus.LocusGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SupervisionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: SupervisionManager
    private lateinit var notifications: SupervisionNotifications
    private lateinit var wakeLock: PowerManager.WakeLock
    private var work: Job? = null
    private var postedOutage: Long? = null
    private var lastPoll = 0L
    private var heldDeadline: Long? = null

    override fun onCreate() {
        super.onCreate()
        manager = SupervisionManager.get(this)
        notifications = SupervisionNotifications(this)
        notifications.createChannels()
        wakeLock =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrackGlance:supervision")
                .apply { setReferenceCounted(false) }
        val notification = notifications.ongoing()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            startForeground(
                SupervisionNotifications.STATUS_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        else startForeground(SupervisionNotifications.STATUS_ID, notification)
        manager.changed = ::stateChanged
        stateChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (manager.machine.settings.mode == SupervisionMode.OFF) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (work == null)
            work = scope.launch {
                while (isActive) {
                    drive()
                    delay(WORK_TICK_MILLIS)
                }
            }
        return START_STICKY
    }

    private fun stateChanged() {
        val machine = manager.machine
        if (!machine.alert) {
            notifications.clear()
            postedOutage = null
        }
        val nextDeadline =
            machine.deadline
                ?: if (machine.launching)
                    heldDeadline ?: (SystemClock.elapsedRealtime() + RECORDING_POLL_MILLIS)
                else null
        if (nextDeadline != null && (nextDeadline != heldDeadline || !wakeLock.isHeld)) {
            wakeLock.acquire(
                (nextDeadline - SystemClock.elapsedRealtime() + WAKE_MARGIN_MILLIS).coerceIn(
                    WAKE_MARGIN_MILLIS,
                    MAX_WAKE_MILLIS,
                )
            )
        }
        if (nextDeadline == null && wakeLock.isHeld) wakeLock.release()
        heldDeadline = nextDeadline
        if (machine.settings.mode == SupervisionMode.OFF) stopSelf()
    }

    private suspend fun drive() {
        val machine = manager.machine
        recheckNotifications()
        if (machine.settings.mode == SupervisionMode.OFF) return
        val now = SystemClock.elapsedRealtime()
        val actionDue =
            machine.due() ||
                manager.manualRequested ||
                (machine.alert && postedOutage != machine.outageId)
        if (machine.needsPolling && (actionDue || now - lastPoll >= RECORDING_POLL_MILLIS)) {
            val generation = machine.generation
            val snapshot =
                withContext(Dispatchers.IO) { LocusGateway(this@SupervisionService).readSnapshot() }
            if (generation != machine.generation) return
            manager.observeNow(snapshot)
            lastPoll = SystemClock.elapsedRealtime()
        }
        machine.tick()
        val manual = manager.manualRequested
        manager.manualRequested = false
        if (!manager.launchBusy) {
            val token = machine.beginLaunch(manual)
            if (token != null) {
                manager.launchBusy = true
                manager.publish()
                scope.launch {
                    try {
                        val result =
                            WatchAppLauncher.create(applicationContext).launch {
                                val snapshot =
                                    withContext(Dispatchers.IO) {
                                        LocusGateway(this@SupervisionService).readSnapshot()
                                    }
                                if (machine.generation == token) manager.observeNow(snapshot)
                                machine.generation == token && machine.eligible
                            }
                        machine.launchResult(token, result == WatchAppLaunchResult.STARTED)
                    } finally {
                        manager.launchBusy = false
                        manager.publish()
                    }
                }
            }
        }
        postAlertIfNeeded()
        manager.publish()
    }

    private fun postAlertIfNeeded() {
        val machine = manager.machine
        // UNKNOWN retains the existing alert, but cannot post a new one.
        if (!machine.alert || machine.recording != SupervisionRecording.RECORDING) return
        if (postedOutage != machine.outageId && notifications.available()) {
            notifications.alert(machine.sources, machine.outageId)
            postedOutage = machine.outageId
        }
    }

    private fun recheckNotifications() {
        manager.blocked = !notifications.available()
        if (manager.blocked && manager.machine.settings.mode == SupervisionMode.NOTIFY) {
            manager.configure(
                SupervisionSettings(SupervisionMode.OFF, manager.machine.settings.delaySeconds)
            )
        }
    }

    override fun onDestroy() {
        manager.changed = null
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val WORK_TICK_MILLIS = 250L
        const val RECORDING_POLL_MILLIS = 5000L
        const val WAKE_MARGIN_MILLIS = 10_000L
        const val MAX_WAKE_MILLIS = 100_000L
    }
}
