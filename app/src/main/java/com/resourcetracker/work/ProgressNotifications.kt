package com.resourcetracker.work

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.resourcetracker.R
import com.resourcetracker.reminder.NotificationChannels
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun ingesting(reelUrl: String): Notification {
        val label = reelUrl.substringAfterLast('/').take(40)
        return NotificationCompat.Builder(context, NotificationChannels.CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.notification_ingest_title))
            .setContentText(context.getString(R.string.notification_ingest_body, label))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
