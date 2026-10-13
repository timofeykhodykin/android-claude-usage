package dev.claudeusage.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.claudeusage.Graph
import dev.claudeusage.MainActivity
import dev.claudeusage.R
import dev.claudeusage.data.Format
import dev.claudeusage.data.MINUTE
import dev.claudeusage.data.UsageState
import dev.claudeusage.ui.Texts

/** Optional notifications: the session limit reset, or it passed a threshold. Both off by default. */
object Notifier {
    private const val CHANNEL = "limits"
    private const val ID_RESET = 1
    private const val ID_THRESHOLD = 2

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.notification_channel_description)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun check(context: Context, previous: UsageState, current: UsageState, now: Long = System.currentTimeMillis()) {
        val settings = Graph.settings.value
        if (!canPost(context)) return
        val prefs = context.getSharedPreferences("notifier", Context.MODE_PRIVATE)
        val texts = Texts(context)

        if (settings.notifyReset) {
            // The window that just ended, whether or not a fetch already replaced it.
            val ended = listOfNotNull(current.snapshot?.session, previous.snapshot?.session)
                .firstOrNull { w -> w.resetsAt?.let { it <= now && now - it < 30 * MINUTE } == true && w.utilization > 0 }
            val resetAt = ended?.resetsAt
            if (resetAt != null && prefs.getLong("resetNotified", 0) != resetAt) {
                prefs.edit().putLong("resetNotified", resetAt).apply()
                post(context, ID_RESET, context.getString(R.string.notification_reset_title),
                    context.getString(R.string.notification_reset_text, Format.percent(ended.utilization)))
            }
        }

        val threshold = settings.notifyThreshold
        val session = current.snapshot?.session
        if (threshold > 0 && session != null && session.current(now) >= threshold) {
            val window = session.resetsAt ?: 0
            if (prefs.getLong("thresholdNotified", -1) != window) {
                prefs.edit().putLong("thresholdNotified", window).apply()
                post(context, ID_THRESHOLD,
                    context.getString(R.string.notification_threshold_title, Format.percent(session.current(now))),
                    texts.resetAbsolute(session, now))
            }
        }
    }

    private fun post(context: Context, id: Int, title: String, text: String) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_bars_notification)
            .setColor(context.getColor(R.color.accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
        }
    }
}
