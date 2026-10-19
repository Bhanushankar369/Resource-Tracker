package com.resourcetracker.reminder

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.resourcetracker.MainActivity
import com.resourcetracker.R
import com.resourcetracker.data.settings.SettingsStore
import com.resourcetracker.domain.ReminderTime
import com.resourcetracker.domain.model.Card
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsStore: SettingsStore,
    private val notificationChannels: NotificationChannels,
) {
    /**
     * Android 13+ requires a runtime grant before any notification can be posted.
     * Failing here is normal, not a bug - the UI prompts for this separately.
     */
    private val canPostNotifications: Boolean
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    /**
     * Posts the 6pm digest. No-ops when there is nothing to review, so a user with an
     * empty queue is never nagged.
     *
     * @return true if a notification was posted.
     */
    fun notifyPendingReview(cards: List<Card>): Boolean {
        if (cards.isEmpty() || !canPostNotifications) return false

        val count = cards.size
        // A plurals resource, so locales with more than two plural forms get a correct
        // title rather than the English "one/other" pair.
        val title = context.resources.getQuantityString(R.plurals.reminder_title, count, count)

        val preview = cards.take(3).joinToString("\n") { "• ${it.title}" }
        val hidden = count - 3
        val more = if (hidden > 0) {
            context.resources.getQuantityString(R.plurals.reminder_more, hidden, hidden)
        } else {
            ""
        }

        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DAILY_REVIEW)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_body_preview, cards.first().title))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(listOf(preview, more).filter { it.isNotBlank() }.joinToString("\n\n")),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_OPEN_TAB_PENDING))
            .build()

        return post(NOTIFICATION_ID_DAILY, notification)
    }

    /**
     * Posts a sample notification showing the currently configured reminder time, so
     * the user can verify permissions and channel settings without waiting for the
     * real one. Uses a different notification id so it does not collide with the digest.
     */
    suspend fun notifyTestReminder(): Boolean {
        if (!canPostNotifications) return false

        val minuteLabel = ReminderTime.labelFor(settingsStore.currentReminderTimeMinutes())
        val notification = NotificationCompat.Builder(context, NotificationChannels.CHANNEL_DAILY_REVIEW)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_test_title, minuteLabel))
            .setContentText(context.getString(R.string.reminder_test_body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(MainActivity.EXTRA_OPEN_TAB_PENDING))
            .build()

        return post(NOTIFICATION_ID_TEST, notification)
    }

    /**
     * Posts a notification, re-checking the runtime permission at the call site.
     *
     * The permission can be revoked between the caller building the notification and
     * this running, and `notify` throws SecurityException in that case. A reminder that
     * throws inside a BroadcastReceiver would be silently dropped, so it is caught and
     * reported as a false return.
     *
     * MissingPermission is suppressed because the check is right here, inline, where
     * lint's dataflow cannot follow it from the [canPostNotifications] property.
     */
    @SuppressLint("MissingPermission")
    private fun post(id: Int, notification: Notification): Boolean {
        if (!canPostNotifications) return false
        return try {
            // Idempotent, and cheap. Posting to a channel that does not exist yet is
            // silently dropped by the platform, so never rely on the caller having
            // reached Application.onCreate before this runs.
            notificationChannels.createAll()

            // notify() does not throw when the channel is blocked or was never really
            // registered - the notification is just dropped and the caller believes it
            // succeeded. Because the caller records the day as "reminder sent", a false
            // success here locks every later attempt out until tomorrow, which is how a
            // broken reminder looks permanently fixed-but-silent.
            if (!isChannelDeliverable(notification.channelId)) {
                Log.w(TAG, "Channel '${notification.channelId}' is blocked or missing")
                return false
            }

            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification permission revoked before posting", e)
            false
        }
    }

    /**
     * True when the channel exists and the user has not switched it off. An unknown
     * channel id is treated as not deliverable, because that is exactly the case the
     * platform drops without complaint.
     */
    private fun isChannelDeliverable(channelId: String?): Boolean {
        val id = channelId ?: return false
        val manager = context.getSystemService<NotificationManager>() ?: return false
        val channel = manager.getNotificationChannel(id) ?: return false
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun openAppIntent(extra: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(extra, true)
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val NOTIFICATION_ID_DAILY = 6200
        const val NOTIFICATION_ID_TEST = 6201
        private const val REQUEST_OPEN_APP = 6202
        private const val TAG = "ReminderNotifier"
    }
}
