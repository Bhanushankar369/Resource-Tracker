package com.resourcetracker.work

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resourcetracker.data.repository.CardRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException

/**
 * Replays a locally-recorded review / snooze / delete to the backend.
 *
 * Only one pending action per card is ever outstanding, because
 * [com.resourcetracker.data.repository.PendingAction] is a single column that each
 * new user action overwrites. The newest intent wins, which is what a user expects:
 * snooze then delete means deleted.
 */
@HiltWorker
class CardActionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val cardRepository: CardRepository,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val cardId = inputData.getString(KEY_CARD_ID) ?: return Result.failure()

        return try {
            cardRepository.pushPendingActions()

            // pushPendingActions absorbs per-row network errors so one bad card cannot
            // block the others. So instead of relying on a thrown exception, check
            // whether this card's action actually made it.
            if (cardRepository.isActionPending(cardId)) {
                if (runAttemptCount < MAX_ATTEMPTS) {
                    Log.w(TAG, "Action for $cardId not acknowledged yet; retrying")
                    Result.retry()
                } else {
                    Log.e(TAG, "Giving up pushing action for $cardId")
                    Result.failure()
                }
            } else {
                Result.success()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network failure pushing action for $cardId", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure pushing action for $cardId", e)
            Result.failure()
        }
    }

    companion object {
        const val KEY_CARD_ID = "card_id"
        private const val TAG = "CardActionWorker"
        private const val MAX_ATTEMPTS = 5
    }
}
