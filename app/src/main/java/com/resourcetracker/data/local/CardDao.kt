package com.resourcetracker.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.resourcetracker.domain.model.CardStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): CardEntity?

    @Query("SELECT * FROM cards WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<CardEntity?>

    /**
     * The 6pm digest: everything still awaiting the user, including snoozed cards
     * whose snooze date has arrived.
     */
    @Query(
        """
        SELECT * FROM cards
        WHERE status = 'PENDING'
           OR (status = 'SNOOZED' AND snoozeUntilEpochDay IS NOT NULL AND snoozeUntilEpochDay <= :todayEpochDay)
        ORDER BY createdAtEpochMillis DESC
        """,
    )
    fun observeDue(todayEpochDay: Long): Flow<List<CardEntity>>

    @Query(
        """
        SELECT * FROM cards
        WHERE status = 'PENDING'
           OR (status = 'SNOOZED' AND snoozeUntilEpochDay IS NOT NULL AND snoozeUntilEpochDay <= :todayEpochDay)
        ORDER BY createdAtEpochMillis DESC
        """,
    )
    suspend fun getDue(todayEpochDay: Long): List<CardEntity>

    /**
     * Everything the Review tab surfaces: actionable cards (pending, snoozed-and-due)
     * plus FAILED cards. FAILED is here because a failed card is otherwise invisible
     * - it is blocked from being re-added by the reel-url dedup, so the only way out
     * is a retry, which has to be reachable. It is deliberately NOT in [observeDue]:
     * the 6pm digest must not nag about cards that never got summarised.
     */
    @Query(
        """
        SELECT * FROM cards
        WHERE status = 'PENDING'
           OR (status = 'SNOOZED' AND snoozeUntilEpochDay IS NOT NULL AND snoozeUntilEpochDay <= :todayEpochDay)
           OR status = 'FAILED'
        ORDER BY createdAtEpochMillis DESC
        """,
    )
    fun observeNeedsAttention(todayEpochDay: Long): Flow<List<CardEntity>>

    @Query("SELECT COUNT(*) FROM cards WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    @Query(
        """
        SELECT * FROM cards
        WHERE status IN ('QUEUED', 'PROCESSING')
        ORDER BY createdAtEpochMillis ASC
        """,
    )
    fun observeInFlight(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE status = 'REVIEWED' ORDER BY reviewedAtEpochMillis DESC")
    fun observeReviewed(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE reelUrl = :reelUrl AND status != 'DELETED' LIMIT 1")
    suspend fun findByReelUrl(reelUrl: String): CardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun hardDelete(id: String)

    @Query("SELECT * FROM cards WHERE pendingAction IS NOT NULL ORDER BY createdAtEpochMillis ASC")
    suspend fun getPendingActions(): List<CardEntity>

    @Query("UPDATE cards SET pendingAction = NULL WHERE id = :id")
    suspend fun clearPendingAction(id: String)

    @Query("UPDATE cards SET lastError = :message WHERE id = :id")
    suspend fun setLastError(id: String, message: String?)

    @Query("SELECT COUNT(*) FROM cards WHERE status = 'QUEUED' OR status = 'PROCESSING'")
    fun observeInFlightCount(): Flow<Int>

    /**
     * Applies a server-authoritative card, but never resurrects a card the user
     * has already acted on locally.
     *
     * The reminder state machine is user-owned state. If the user reviewed or
     * deleted a card while a sync was in flight, a stale server row must not
     * flip it back to PENDING and start reminding them again.
     */
    @Transaction
    suspend fun mergeServerCard(incoming: CardEntity) {
        val existing = findById(incoming.id) ?: return run {
            upsert(incoming)
            return
        }
        val userOwned = existing.status in setOf(CardStatus.REVIEWED, CardStatus.DELETED)
        if (userOwned) return

        // A QUEUED/PROCESSING row is the server's "still in flight" state: the job has
        // not produced a card yet. It must not erase the local jobId - that is the
        // handle JobPollWorker uses to learn the job finished. On a terminal state
        // (PENDING/FAILED) the job is over and the poller no longer needs it.
        val jobStillInFlight =
            incoming.status == CardStatus.QUEUED || incoming.status == CardStatus.PROCESSING
        // Never clobber a write that has not reached the server yet.
        upsert(
            incoming.copy(
                pendingAction = existing.pendingAction,
                jobId = if (jobStillInFlight) existing.jobId else null,
            ),
        )
    }
}
