package com.resourcetracker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single place that knows every background job the app can run.
 *
 * Naming rules:
 *  - unique work names are prefixed by kind so `cancelByTag` can target a kind
 *  - one-time work is always unique per card, so a double share cannot create two
 *    jobs for the same reel
 */
@Singleton
class WorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager: WorkManager get() = WorkManager.getInstance(context)

    private val networkRequired = Constraints.Builder()
        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
        .build()

    // ------------------------------------------------------------------ ingest

    /**
     * Uploads a saved reel to the backend.
     *
     * ExistingWorkPolicy.KEEP: if a job for this card is already in flight, drop the
     * new request rather than racing two uploads of the same URL.
     */
    fun enqueueIngest(
        cardId: String,
        note: String?,
        description: String? = null,
        creator: String? = null,
    ) {
        val data = Data.Builder()
            .putString(IngestReelWorker.KEY_CARD_ID, cardId)
            .putString(IngestReelWorker.KEY_NOTE, note)
            .putString(IngestReelWorker.KEY_DESCRIPTION, description)
            .putString(IngestReelWorker.KEY_CREATOR, creator)
            .build()

        val request = OneTimeWorkRequestBuilder<IngestReelWorker>()
            .setInputData(data)
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_INGEST)
            .addTag(cardTag(cardId))
            .build()

        workManager.enqueueUniqueWork(ingestName(cardId), ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Polls the backend for the result of the ingest job for [cardId].
     *
     * REPLACE so a re-ingest (retry) points the poller at the fresh job id; the
     * worker exits immediately when the card leaves PROCESSING.
     */
    fun enqueueJobPoll(cardId: String) {
        val request = OneTimeWorkRequestBuilder<JobPollWorker>()
            .setInputData(Data.Builder().putString(JobPollWorker.KEY_CARD_ID, cardId).build())
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(TAG_POLL)
            .addTag(cardTag(cardId))
            .build()

        workManager.enqueueUniqueWork(pollName(cardId), ExistingWorkPolicy.REPLACE, request)
    }

    // ------------------------------------------------------------- action push

    /** Replays a local review/snooze/delete to the backend. */
    fun enqueueActionPush(cardId: String) {
        val request = OneTimeWorkRequestBuilder<CardActionWorker>()
            .setInputData(Data.Builder().putString(CardActionWorker.KEY_CARD_ID, cardId).build())
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .addTag(TAG_PUSH)
            .addTag(cardTag(cardId))
            .build()

        workManager.enqueueUniqueWork(pushName(cardId), ExistingWorkPolicy.KEEP, request)
    }

    // ------------------------------------------------------------------- sync

    /**
     * Periodic reconciliation with the backend.
     *
     * 15 minutes is the floor WorkManager allows for periodic work, and even that
     * is only a floor - the real interval is chosen by the system to save battery,
     * so treat it as "roughly often", not "every 15 minutes". State that must be
     * exact (the 6pm reminder) uses AlarmManager instead.
     */
    fun schedulePeriodicSync() {
        val request = PeriodicWorkRequestBuilder<CardSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkRequired)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG_SYNC)
            .build()

        workManager.enqueueUniquePeriodicWork(
            WORK_PERIODIC_SYNC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun syncNow() {
        val request = OneTimeWorkRequestBuilder<CardSyncWorker>()
            .setConstraints(networkRequired)
            .addTag(TAG_SYNC)
            .build()
        workManager.enqueueUniqueWork(WORK_ONE_SHOT_SYNC, ExistingWorkPolicy.REPLACE, request)
    }

    // --------------------------------------------------------------- reminders

    fun enqueueReminderCheck() {
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .addTag(TAG_REMINDER)
            .build()
        // REPLACE, not KEEP: two alarms firing close together should produce one
        // notification, not a duplicate.
        workManager.enqueueUniqueWork(WORK_REMINDER, ExistingWorkPolicy.REPLACE, request)
    }

    fun observeIngestState(cardId: String): Flow<WorkInfo.State?> =
        workManager.getWorkInfosForUniqueWorkFlow(ingestName(cardId))
            .map { it.firstOrNull()?.state }

    companion object {
        const val WORK_PERIODIC_SYNC = "card-sync-periodic"
        const val WORK_ONE_SHOT_SYNC = "card-sync-now"
        const val WORK_REMINDER = "daily-reminder-check"

        const val TAG_INGEST = "ingest"
        const val TAG_PUSH = "card-action-push"
        const val TAG_SYNC = "card-sync"
        const val TAG_POLL = "job-poll"
        const val TAG_REMINDER = "reminder"

        fun cardTag(cardId: String) = "card:$cardId"
        fun ingestName(cardId: String) = "ingest-$cardId"
        fun pushName(cardId: String) = "push-$cardId"
        fun pollName(cardId: String) = "poll-$cardId"
    }
}
