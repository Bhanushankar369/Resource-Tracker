package com.resourcetracker.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resourcetracker.data.local.CardDao
import com.resourcetracker.data.remote.BackendApi
import com.resourcetracker.data.repository.toEntity
import com.resourcetracker.domain.model.CardStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/**
 * Follows one backend job to completion.
 *
 * IngestReelWorker only uploads; the summarisation happens server side and the phone
 * cannot know when it finished without asking. This worker polls GET /v1/jobs/{id}
 * for the card's stored jobId until the job is terminal (succeeded -> merge the card,
 * failed -> mark FAILED with the server's reason), then stops.
 *
 * While the job is still running it backs off; after [MAX_ATTEMPTS] it gives up and
 * lets the periodic CardSyncWorker - which is now scheduled at app start - close the
 * loop later, so a card can never be stuck on "Summarising…" forever.
 */
@HiltWorker
class JobPollWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cardDao: CardDao,
    private val api: BackendApi,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cardId = inputData.getString(KEY_CARD_ID) ?: return Result.failure()
        val card = cardDao.findById(cardId) ?: return Result.success()
        val jobId = card.jobId ?: return Result.success()
        // Already resolved locally (user acted, or a sync beat us to it).
        if (card.status != CardStatus.PROCESSING) return Result.success()

        return try {
            val job = api.jobStatus(jobId)
            when (job.status) {
                "succeeded" -> {
                    val dto = job.card
                    if (dto != null) {
                        cardDao.mergeServerCard(dto.toEntity())
                    } else {
                        markFailed(cardId, "The summary is missing.")
                    }
                    Result.success()
                }

                "failed" -> {
                    markFailed(cardId, job.error ?: "The reel could not be processed.")
                    Result.success()
                }

                else -> {
                    // Still queued. Keep polling, but never forever.
                    if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
                }
            }
        } catch (e: HttpException) {
            if (e.code() == 404) {
                // The job row is gone; the periodic sync decides the card's fate.
                markFailed(cardId, "The processing job is no longer available.")
                Result.success()
            } else if (runAttemptCount >= MAX_ATTEMPTS) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error polling job $jobId", e)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error polling job $jobId", e)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    private suspend fun markFailed(cardId: String, reason: String) {
        cardDao.findById(cardId)?.let { current ->
            cardDao.upsert(
                current.copy(
                    status = CardStatus.FAILED,
                    lastError = reason.take(200),
                    jobId = null,
                ),
            )
        }
    }

    companion object {
        const val KEY_CARD_ID = "card_id"

        /**
         * Backoff starts at 10s and doubles each attempt, so MAX_ATTEMPTS covers
         * roughly an hour of polling before the periodic sync takes over.
         */
        private const val MAX_ATTEMPTS = 24
        private const val TAG = "JobPollWorker"
    }
}