package com.resourcetracker.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.resourcetracker.domain.model.Card
import com.resourcetracker.domain.model.CardStatus
import java.time.Instant

/**
 * Local cache of a knowledge card.
 *
 * IMPORTANT: this table intentionally stores ONLY the summary, the insights and
 * the reel URL. The transcript, the downloaded audio/video and the OCR frames all
 * live in the backend job workspace and are deleted before the job completes, so
 * there is nothing transient to store here. See backend/app/pipeline/workspace.py.
 */
@Entity(
    tableName = "cards",
    indices = [
        Index(value = ["status"]),
        Index(value = ["snoozeUntilEpochDay"]),
        Index(value = ["reelUrl"]),
    ],
)
data class CardEntity(
    @PrimaryKey
    val id: String,
    val reelUrl: String,
    val title: String,
    val summary: String,
    val description: String = "",
    val hashtags: List<String> = emptyList(),
    val creatorName: String? = null,
    val creatorUsername: String? = null,
    val creatorBio: String? = null,
    val keyInsights: List<String>,
    val actionItems: List<String>,
    val topics: List<String>,
    val status: CardStatus,
    val createdAtEpochMillis: Long,
    val reviewedAtEpochMillis: Long? = null,

    /** Epoch day (LocalDate.toEpochDay) the card becomes due again. Null when not snoozed. */
    val snoozeUntilEpochDay: Long? = null,
    val providerName: String? = null,
    val thumbnailUrl: String? = null,
    val lastError: String? = null,

    /**
     * The backend job currently responsible for this card, if the ingest succeeded.
     * Set by IngestReelWorker and used by JobPollWorker to ask for the result; the
     * server never sends it back, so it is cleared when a poll resolves the card.
     */
    val jobId: String? = null,

    /**
     * A user action that has not yet been accepted by the backend, encoded as
     * `REVIEWED`, `SNOOZED:<epochDay>` or `DELETED`.
     *
     * This is what makes the app offline-first without losing writes: the UI
     * changes the row immediately, and CardSyncWorker replays the action until the
     * server acknowledges it and clears this field.
     */
    val pendingAction: String? = null,
)

fun CardEntity.toDomain(): Card = Card(
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
    status = status,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    reviewedAt = reviewedAtEpochMillis?.let(Instant::ofEpochMilli),
    snoozeUntilEpochDay = snoozeUntilEpochDay,
    providerName = providerName,
    thumbnailUrl = thumbnailUrl,
    lastError = lastError,
)

fun Card.toEntity(): CardEntity = CardEntity(
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
    status = status,
    createdAtEpochMillis = createdAt.toEpochMilli(),
    reviewedAtEpochMillis = reviewedAt?.toEpochMilli(),
    snoozeUntilEpochDay = snoozeUntilEpochDay,
    providerName = providerName,
    thumbnailUrl = thumbnailUrl,
    lastError = lastError,
)
