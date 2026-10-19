package com.resourcetracker.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.resourcetracker.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Re-registers the daily alarm after events that wipe it.
 *
 * - BOOT_COMPLETED / MY_PACKAGE_REPLACED: every alarm is dropped on reboot and on app
 *   update, so without this the 6pm reminder silently stops forever.
 * - TIMEZONE_CHANGED / TIME_SET: the alarm is stored as an absolute UTC instant, so a
 *   timezone change would otherwise fire the reminder at the wrong local hour.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var reminderScheduler: DailyReminderScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> {
                Log.i(TAG, "Re-arming daily reminder after ${intent.action}")
                val result = goAsync()
                applicationScope.launch {
                    try {
                        reminderScheduler.schedule()
                    } finally {
                        result.finish()
                    }
                }
            }

            else -> Unit
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
