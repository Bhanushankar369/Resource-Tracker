package com.resourcetracker.data.repository

import com.resourcetracker.core.ReelUrl
import com.resourcetracker.data.local.CardDao
import com.resourcetracker.data.local.CardEntity
import com.resourcetracker.data.local.toDomain
import com.resourcetracker.data.remote.BackendApi
import com.resourcetracker.data.remote.CardDto
import com.resourcetracker.data.remote.SubmitReelRequest
import com.resourcetracker.data.remote.UpdateCardRequest
import com.resourcetracker.domain.model.Card
import com.resourcetracker.domain.model.CardStatus
import com.resourcetracker.work.WorkScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AddReelResult {
    data class Accepted(val cardId: String) : AddReelResult
    data object NotAUrl : AddReelResult
    data object Duplicate : AddReelResult
}

@Singleton
class CardRepository @Inject constructor(
    private val cardDao: CardDao,
    private val api: BackendApi,
    private val workScheduler: WorkScheduler,
    private val clock: Clock,
) {

    // ------------------------------------------------------------------ reads

    /**
     * Cards the Review tab shows: pending, snoozed-and-due, and FAILED (which must be
     * reachable for a retry). The 6pm digest stays separate - see CardDao.observeDue.
     */
    fun observeDue(): Flow<List<Card>> =
        cardDao.observeNeedsAttention(LocalDate.now(clock).toEpochDay()).map { rows -> rows.map { it.toDomain() } }

    fun observeReviewed(): Flow<List<Card>> =
        cardDao.observeReviewed().map { rows -> rows.map { it.toDomain() } }

    fun observeCard(id: String): Flow<Card?> =
        cardDao.observeById(id).map { it?.toDomain() }

    fun observeInFlight(): Flow<List<Card>> =
        cardDao.observeInFlight().map { rows -> rows.map { it.toDomain() } }

    fun observePendingCount(): Flow<Int> = cardDao.observePendingCount()

    fun observeInFlightCount(): Flow<Int> = cardDao.observeInFlightCount()

    // ----------------------------------------------------------------- writes

    /**
     * Local-first submit.
     *
     * The row is written and shown immediately, then a WorkManager job uploads it
     * in the background. This is why a reel saved from the Instagram share sheet
     * appears in the app before any network round trip has finished.
     *
     * [description] and [creator] are the only reel text the app can reliably get
     * hold of: Instagram's share sheet sends the URL, and its metadata endpoints are
     * unreadable without Meta App Review. Anything the user typed wins; otherwise
     * both are dug out of the shared text itself. They make the backend build the
     * card even when no provider can read the reel.
     */
    suspend fun addReel(
        sharedTextOrUrl: String,
        note: String? = null,
        description: String? = null,
        creator: String? = null,
    ): AddReelResult {
        val url = ReelUrl.normalize(ReelUrl.extract(sharedTextOrUrl) ?: sharedTextOrUrl)
            ?: return AddReelResult.NotAUrl

        if (cardDao.findByReelUrl(url) != null) return AddReelResult.Duplicate

        val resolvedDescription = description?.takeIf { it.isNotBlank() }
            ?: ReelUrl.captionFromSharedText(sharedTextOrUrl)
        val resolvedCreator = creator?.takeIf { it.isNotBlank() }
            ?: ReelUrl.creatorFromSharedText(sharedTextOrUrl)

        val cardId = UUID.randomUUID().toString()
        cardDao.upsert(
            CardEntity(
                id = cardId,
                reelUrl = url,
                title = "Summarising…",
                summary = "",
                keyInsights = emptyList(),
                actionItems = emptyList(),
                topics = emptyList(),
                status = CardStatus.QUEUED,
                createdAtEpochMillis = Instant.now(clock).toEpochMilli(),
            ),
        )

        workScheduler.enqueueIngest(
            cardId = cardId,
            note = note,
            description = resolvedDescription.ifBlank { null },
            creator = resolvedCreator?.removePrefix("@"),
        )
        return AddReelResult.Accepted(cardId)
    }

    /** Marks reviewed. The card is never surfaced by the reminder again. */
    suspend fun markReviewed(id: String) = applyAction(
        id = id,
        status = CardStatus.REVIEWED,
        pendingAction = PendingAction.Reviewed,
        snoozeUntilEpochDay = null,
    )

    /** Reminds again from [until]. Defaults to tomorrow, matching the MVP spec. */
    suspend fun snooze(id: String, until: LocalDate = LocalDate.now(clock).plusDays(1)) =
        applyAction(
            id = id,
            status = CardStatus.SNOOZED,
            pendingAction = PendingAction.Snoozed(until.toEpochDay()),
            snoozeUntilEpochDay = until.toEpochDay(),
        )

    /** Soft delete: hidden everywhere, and the tombstone is purged after it syncs. */
    suspend fun delete(id: String) = applyAction(
        id = id,
        status = CardStatus.DELETED,
        pendingAction = PendingAction.Deleted,
        snoozeUntilEpochDay = null,
    )

    /** Puts a FAILED card back in the queue. */
    suspend fun retry(id: String) {
        val existing = cardDao.findById(id) ?: return
        cardDao.upsert(
            existing.copy(
                status = CardStatus.QUEUED,
                title = if (existing.title.isBlank()) "Summarising…" else existing.title,
                lastError = null,
                jobId = null,
            ),
        )
        workScheduler.enqueueIngest(
            cardId = id,
            note = null,
        )
    }
    private suspend fun applyAction(
        id: String,
        status: CardStatus,
        pendingAction: PendingAction,
        snoozeUntilEpochDay: Long?,
    ) {
        val existing = cardDao.findById(id) ?: return
        cardDao.upsert(
            existing.copy(
                status = status,
                reviewedAtEpochMillis = if (status == CardStatus.REVIEWED) {
                    Instant.now(clock).toEpochMilli()
                } else {
                    existing.reviewedAtEpochMillis
                },
                snoozeUntilEpochDay = snoozeUntilEpochDay,
                pendingAction = pendingAction.encode(),
                lastError = null,
            ),
        )
        workScheduler.enqueueActionPush(id)
    }

    // ------------------------------------------------------------------- sync

    /**
     * Two-phase sync: replay local writes, then pull server truth.
     *
     * Push first is deliberate. If a review happened while the app was offline, the
     * backend must hear about it before the pull, otherwise the pull would hand back
     * the pre-review row and the card would appear pending again.
     */
    suspend fun sync(): SyncReport {
        val pushed = pushPendingActions()
        val pulled = pullFromServer()
        return SyncReport(pushedActions = pushed, pulledCards = pulled)
    }

    suspend fun pushPendingActions(): Int {
        var pushed = 0
        for (row in cardDao.getPendingActions()) {
            val ok = runCatching { pushAction(row) }.getOrDefault(false)
            if (ok) {
                cardDao.clearPendingAction(row.id)
                if (row.status == CardStatus.DELETED) cardDao.hardDelete(row.id)
                pushed++
            } else if (row.status != CardStatus.REVIEWED && row.status != CardStatus.DELETED) {
                // Only surface a sync error on a card the user can still act on. A
                // REVIEWED card the user has finished with, or a DELETED one that is
                // about to be purged, must not suddenly show "Could not sync".
                cardDao.setLastError(row.id, "Could not sync to server; will retry")
            }
        }
        return pushed
    }

    /**
     * @return true when there is nothing left to do for this row, so the caller can
     *   clear the pending action.
     */
    private suspend fun pushAction(row: CardEntity): Boolean {
        val action = PendingAction.decode(row.pendingAction) ?: return true

        return try {
            when (action) {
                is PendingAction.Deleted -> api.deleteCard(row.id).isSuccessful
                is PendingAction.Reviewed -> api.updateCard(
                    row.id,
                    UpdateCardRequest(status = CardStatus.REVIEWED.name),
                ).isSuccessful

                is PendingAction.Snoozed -> api.updateCard(
                    row.id,
                    UpdateCardRequest(
                        status = CardStatus.SNOOZED.name,
                        snoozeUntilEpochDay = action.untilEpochDay,
                    ),
                ).isSuccessful
            }
        } catch (e: retrofit2.HttpException) {
            // 404 means the server never received the reel (ingest failed or the row
            // was already purged). The local intent is still the user's truth, so treat
            // it as done instead of retrying a request that can never succeed.
            if (e.code() == 404) true else false
        }
    }

    suspend fun pullFromServer(): Int {
        val remote = api.listCards()
        remote.forEach { dto -> cardDao.mergeServerCard(dto.toEntity()) }
        return remote.size
    }

    /** Cleared once the backend has acknowledged a locally recorded action. */
    suspend fun isActionPending(id: String): Boolean =
        cardDao.findById(id)?.pendingAction != null

    data class SyncReport(val pushedActions: Int, val pulledCards: Int)
}

/**
 * Encoded user intent waiting to reach the backend.
 *
 * Modelled as a sealed class rather than a bare string so the encoding is
 * type-checked and so a future extra action does not silently collide.
 */
sealed interface PendingAction {
    fun encode(): String

    data object Reviewed : PendingAction {
        override fun encode() = "REVIEWED"
    }

    data object Deleted : PendingAction {
        override fun encode() = "DELETED"
    }

    data class Snoozed(val untilEpochDay: Long) : PendingAction {
        override fun encode() = "SNOOZED:$untilEpochDay"
    }

    companion object {
        fun decode(raw: String?): PendingAction? = when {
            raw == null -> null
            raw == Reviewed.encode() -> Reviewed
            raw == Deleted.encode() -> Deleted
            raw.startsWith("SNOOZED:") ->
                raw.removePrefix("SNOOZED:").toLongOrNull()?.let(::Snoozed)
            else -> null
        }
    }
}

// ------------------------------------------------------------------ mapping

private val CARD_STATUS_BY_NAME: Map<String, CardStatus> =
    CardStatus.entries.associateBy { it.name }

/** Tolerant mapping: an unknown server status must not crash the client. */
internal fun CardDto.toStatusOrPending(): CardStatus =
    CARD_STATUS_BY_NAME[status.uppercase()] ?: CardStatus.PENDING

internal fun CardDto.toEntity(): CardEntity = CardEntity(
    id = id,
    reelUrl = reelUrl,
    title = title,
    summary = summary,
    description = description,
    hashtags = hashtags,
    creatorName = creatorName,
    creatorUsername = creatorUsername,
    creatorBio = creatorBio,
    keyInsights = keyInsights,
    actionItems = actionItems,
    topics = topics,
    status = toStatusOrPending(),
    createdAtEpochMillis = runCatching { Instant.parse(createdAt).toEpochMilli() }
        .getOrDefault(System.currentTimeMillis()),
    reviewedAtEpochMillis = reviewedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
    snoozeUntilEpochDay = snoozeUntilEpochDay,
    providerName = provider,
    thumbnailUrl = thumbnailUrl,
    lastError = lastError,
    // The backend never tracks job ids on the card and never re-sends a user action;
    // the DAO merges those from the local copy.
    jobId = null,
    pendingAction = null,
)
