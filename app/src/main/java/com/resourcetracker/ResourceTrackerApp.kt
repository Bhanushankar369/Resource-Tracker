package com.resourcetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.resourcetracker.reminder.NotificationChannels
import com.resourcetracker.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entrypoint. Named in AndroidManifest.xml.
 *
 * Two jobs, both of which silently break things if missed:
 *
 *  1. `@HiltAndroidApp` generates the Hilt singleton component that every
 *     `@AndroidEntryPoint` and `@HiltWorker` depends on. Without this the app cannot
 *     start.
 *
 *  2. `Configuration.Provider` supplies WorkManager's configuration. The manifest
 *     removes WorkManager's default `WorkManagerInitializer` precisely so that this
 *     configuration is used - without the Hilt worker factory, every `@HiltWorker`
 *     fails to instantiate at runtime with a confusing error rather than at build
 *     time.
 */
@HiltAndroidApp
class ResourceTrackerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var workScheduler: WorkScheduler

    @Inject
    lateinit var notificationChannels: NotificationChannels

    override fun onCreate() {
        super.onCreate()
        // Notification channels must exist before anything posts to them. On Android 8+
        // a notify() call naming a channel that was never created is dropped without
        // any error, which is why the 6pm reminder could look perfectly scheduled and
        // still never appear. Creating them here covers every entrypoint: activity,
        // AlarmManager broadcast and WorkManager worker all run in this process.
        notificationChannels.createAll()
        // The periodic sync is the safety net that resolves any card a JobPollWorker
        // gave up on (or that was ever submitted without one). Scheduling it here
        // guarantees it exists; enqueuing happens on first use of WorkManager, which
        // is before this returns. A one-shot sync on cold start additionally clears
        // cards that were left stuck on PROCESSING by older app versions with no
        // server round trip - the server is authoritative for those.
        workScheduler.schedulePeriodicSync()
        workScheduler.syncNow()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.INFO)
            .build()
}
