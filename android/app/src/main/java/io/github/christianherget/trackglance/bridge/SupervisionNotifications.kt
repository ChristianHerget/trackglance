package io.github.christianherget.trackglance.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import io.github.christianherget.trackglance.bridge.core.SupervisionSource

internal class SupervisionNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    STATUS_CHANNEL,
                    context.getString(R.string.supervision_channel_status),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                        ALERT_CHANNEL,
                        context.getString(R.string.supervision_channel_alert),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    )
                    .apply { enableVibration(true) }
            )
        }
    }

    fun available(): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
                manager.getNotificationChannel(ALERT_CHANNEL)?.importance !=
                    NotificationManager.IMPORTANCE_NONE)

    private fun builder(channel: String) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_locus_function)
            .setContentTitle(context.getString(R.string.supervision_title))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent().setClassName(context, MainActivity::class.java.name),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )

    fun ongoing(): Notification =
        builder(STATUS_CHANNEL)
            .setContentText(context.getString(R.string.supervision_ongoing))
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setSilent(true)
            .build()

    fun alert(sources: Set<SupervisionSource>, outage: Long) {
        val text =
            context.getString(
                when {
                    sources.size == 2 -> R.string.supervision_alert_both
                    SupervisionSource.HEART_RATE in sources -> R.string.supervision_alert_hr
                    else -> R.string.supervision_alert_steps
                }
            )
        manager.notify(
            ALERT_ID,
            builder(ALERT_CHANNEL)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setDeleteIntent(action("dismiss", outage))
                .addAction(
                    0,
                    context.getString(R.string.supervision_start),
                    action("start", outage),
                )
                .build(),
        )
    }

    private fun action(action: String, outage: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            if (action == "start") 1 else 2,
            Intent()
                .setClassName(context, SupervisionActionReceiver::class.java.name)
                .setAction(action)
                .setData("trackglance-supervision:$outage/$action".toUri())
                .putExtra("outage", outage),
            PendingIntent.FLAG_IMMUTABLE,
        )

    fun clear() {
        manager.cancel(ALERT_ID)
    }

    companion object {
        const val STATUS_CHANNEL = "watchapp_supervision_status"
        const val ALERT_CHANNEL = "watchapp_supervision_outage"
        const val STATUS_ID = 67
        const val ALERT_ID = 68
    }
}

class SupervisionActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = SupervisionManager.get(context)
        val machine = manager.machine
        if (intent.getLongExtra("outage", -1) != machine.outageId) return
        when (intent.action) {
            "dismiss" -> machine.dismiss(machine.outageId)
            "start" ->
                if (machine.eligible && !manager.launchBusy && !machine.confirming)
                    manager.manualRequested = true
        }
        manager.publish()
    }
}
