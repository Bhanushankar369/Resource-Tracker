package com.resourcetracker.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channels.
 *
 * Channels are created at application start, not at the moment of first
 * notification. On Android 13+ the user picks a channel the first time they are
 * prompted, and a channel that does not exist yet silently drops the notification.
 */
@Singleton
class NotificationChannels @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun createAll() {
        val manager = context.getSystemService<NotificationManager>() ?: return

        val review = NotificationChannel(
            CHANNEL_DAILY_REVIEW,
            "Daily review reminder",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "At 6pm, reminds you which saved reels still need review."
            enableVibration(true)
        }

        val processing = NotificationChannel(
            CHANNEL_PROCESSING,
            "Summarising reels",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress while a saved reel is being turned into a summary card."
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(review, processing))
    }

    companion object {
        const val CHANNEL_DAILY_REVIEW = "daily_review"
        const val CHANNEL_PROCESSING = "processing"
    }
}
