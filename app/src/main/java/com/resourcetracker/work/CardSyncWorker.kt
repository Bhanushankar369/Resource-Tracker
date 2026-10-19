package com.resourcetracker.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resourcetracker.data.repository.CardRepository
import com.resourcetracker.reminder.ReminderDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.time.Clock

/**
 * Reconciles local state with the backend, and is the backstop for the daily reminder.
 *
 * Sync is how a card that was still PROCESSING at submit time turns into a PENDING
 * card with a real summary: the phone has no way to learn the job finished except by
 * asking. Runs on app open and then periodically.
 *
 * It also gives the 6pm digest a second chance. A single exact alarm can be dropped or
 * deferred by the OS, and the user is told to expect a notification at a fixed time, so
 * this asks [ReminderDispatcher] to send it if the time has passed and nothing has gone
 * out today. See that class for why both paths exist.
 */
@HiltWorker
class CardSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cardRepository: CardRepository,
    private val dispatcher: ReminderDispatcher,
    private val clock: Clock,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val report = cardRepository.sync()
        Log.i(
            TAG,
            "Sync complete: pushed=${report.pushedActions} pulled=${report.pulledCards} " +
                "at ${clock.instant()}",
        )
        // Only after the pull, so a card that just became PENDING is already here and
        // counts as due. Failures here must not fail the sync.
        runCatching { dispatcher.runIfDue() }
            .onFailure { Log.w(TAG, "Reminder backstop failed", it) }
        Result.success()
    } catch (e: IOException) {
        Log.w(TAG, "Sync failed - backend unreachable", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    } catch (e: Exception) {
        Log.e(TAG, "Sync failed", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
    }

    companion object {
        private const val TAG = "CardSyncWorker"
        private const val MAX_ATTEMPTS = 4
    }
}
