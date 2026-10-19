package com.resourcetracker.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.resourcetracker.data.local.CardDao
import com.resourcetracker.data.local.CardEntity
import com.resourcetracker.data.remote.BackendApi
import com.resourcetracker.data.remote.SubmitReelRequest
import com.resourcetracker.domain.model.CardStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import retrofit2.HttpException
import java.io.IOException

/**
 * Ships one saved reel URL to the backend and flips the local row to PROCESSING.
 *
 * The heavy lifting (transcript, OCR, LLM summarisation) happens server side. This
 * worker only uploads, which is why it finishes in seconds; the result is then
 * picked up by [JobPollWorker], with [CardSyncWorker] as the periodic safety net.
 */
@HiltWorker
class IngestReelWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cardDao: CardDao,
    private val api: BackendApi,
    private val workScheduler: WorkScheduler,
    private val notifications: ProgressNotifications,
) : CoroutineWorker(appContext, params) {

    private val context: Context = appContext

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val cardId = inputData.getString(KEY_CARD_ID)
        val reelUrl = cardId?.let { cardDao.findById(it)?.reelUrl }.orEmpty()
        return buildForegroundInfo(cardId.orEmpty(), reelUrl)
    }

    override suspend fun doWork(): Result {
        val cardId = inputData.getString(KEY_CARD_ID) ?: return Result.failure()
        val card = cardDao.findById(cardId) ?: return Result.failure()

        // The user reviewed or deleted the reel while the upload was queued.
        if (card.status == CardStatus.REVIEWED || card.status == CardStatus.DELETED) {
            return Result.success()
        }

        setForegroundIfAllowed(card.id, card.reelUrl)

        return try {
            val accepted = api.submitReel(
                SubmitReelRequest(
                    clientCardId = card.id,
                    reelUrl = card.reelUrl,
                    note = inputData.getString(KEY_NOTE),
                    description = inputData.getString(KEY_DESCRIPTION),
                    creator = inputData.getString(KEY_CREATOR),
                ),
            )

            cardDao.upsert(
                card.copy(
                    status = CardStatus.PROCESSING,
                    providerName = accepted.provider ?: card.providerName,
                    lastError = null,
                    jobId = accepted.jobId,
                ),
            )
            // Track the job to completion; without poller the card would sit on
            // "Summarising…" until the next periodic sync.
            workScheduler.enqueueJobPoll(card.id)
            Result.success()
        } catch (e: IOException) {
            Log.w(TAG, "Network failure uploading ${card.reelUrl}", e)
            cardDao.setLastError(card.id, context.getString(com.resourcetracker.R.string.error_waiting_for_network))
            if (runAttemptCount >= MAX_ATTEMPTS) {
                markFailed(card, e.message)
                Result.failure()
            } else {
                Result.retry()
            }
        } catch (e: HttpException) {
            // 4xx means the request itself is wrong; retrying will never help.
            // 408 and 429 are the exceptions - those are transient.
            val transient = e.code() == 408 || e.code() == 429
            Log.e(TAG, "Backend rejected ${card.reelUrl}: HTTP ${e.code()}", e)
            if (transient && runAttemptCount < MAX_ATTEMPTS) {
                cardDao.setLastError(card.id, "Server busy (${e.code()}); retrying")
                Result.retry()
            } else {
                markFailed(card, "Server rejected the request (HTTP ${e.code()})")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure uploading ${card.reelUrl}", e)
            if (runAttemptCount >= MAX_ATTEMPTS) {
                markFailed(card, e.message)
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }

    private suspend fun markFailed(card: CardEntity, reason: String?) {
        cardDao.upsert(
            card.copy(
                status = CardStatus.FAILED,
                lastError = reason?.take(200) ?: "Unknown error",
            ),
        )
    }

    private fun buildForegroundInfo(cardId: String, reelUrl: String): ForegroundInfo {
        val notification = notifications.ingesting(reelUrl)
        val notificationId = cardId.hashCode()
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    /**
     * On Android 13+ a foreground service may only post its notification if
     * POST_NOTIFICATIONS is granted; otherwise `startForeground` throws
     * CannotPostForegroundServiceNotificationException and kills the process. The
     * permission is requested in context from the Settings tab, so it can be
     * legitimately absent when this worker runs - skipping the foreground promotion
     * then just means the upload runs as ordinary background work instead of crashing.
     */
    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    private suspend fun setForegroundIfAllowed(cardId: String, reelUrl: String) {
        if (canPostNotifications()) {
            setForeground(buildForegroundInfo(cardId, reelUrl))
        } else {
            Log.i(TAG, "POST_NOTIFICATIONS not granted; running without a foreground notification")
        }
    }

    companion object {
        const val KEY_CARD_ID = "card_id"
        const val KEY_NOTE = "note"
        const val KEY_DESCRIPTION = "description"
        const val KEY_CREATOR = "creator"
        private const val TAG = "IngestReelWorker"
        private const val MAX_ATTEMPTS = 5
    }
}
