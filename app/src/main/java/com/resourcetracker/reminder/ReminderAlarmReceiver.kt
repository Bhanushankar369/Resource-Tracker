package com.resourcetracker.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.resourcetracker.di.ApplicationScope
import com.resourcetracker.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Alarm target. Does nothing except hand off to WorkManager and re-arm for tomorrow.
 *
 * Keeping the receiver trivial is the point: a BroadcastReceiver has strict execution
 * limits and no network access, so the database read and notification live in
 * [ReminderWorker] instead.
 *
 * Alarms are cleared on reboot, so the schedule is re-registered on every boot - see
 * [BootReceiver].
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject
    lateinit var workScheduler: WorkScheduler

    @Inject
    lateinit var reminderScheduler: DailyReminderScheduler

    @Inject
    @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyReminderScheduler.ACTION_DAILY_REVIEW) return

        Log.i(TAG, "Daily review alarm fired")
        workScheduler.enqueueReminderCheck()

        // A one-shot alarm is removed by the system once it fires. Re-arming here is
        // what turns a single alarm into a daily habit. schedule() reads the stored
        // reminder time from DataStore, so it suspends; goAsync() keeps the process
        // alive until that read finishes.
        val result = goAsync()
        applicationScope.launch {
            try {
                reminderScheduler.schedule()
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ReminderAlarmReceiver"
    }
}
