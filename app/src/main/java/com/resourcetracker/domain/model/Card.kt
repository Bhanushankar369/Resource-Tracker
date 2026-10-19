package com.resourcetracker.domain.model

import java.time.Instant
import java.time.LocalDate

/**
 * Lifecycle of a single knowledge card.
 *
 * The state machine the user actually sees:
 *
 *   QUEUED ──▶ PROCESSING ──▶ PENDING ──▶ REVIEWED   (never reminded again)
 *                  │              │
 *                  │              └──▶ SNOOZED ──▶ PENDING (next day)
 *                  └──▶ FAILED ──▶ PENDING (retry)
 *
 * DELETED is a soft delete: the row is kept with a tombstone so the deletion can
 * be pushed to the backend, but it is filtered out of every query and its media
 * files are already gone.
 */
enum class CardStatus {
    /** Row exists locally, backend job not submitted yet. */
    QUEUED,

    /** Backend is running the provider + LLM pipeline. */
    PROCESSING,

    /** Summary is ready and the user has not acted on it. This is what gets reminded about. */
    PENDING,

    /** User tapped "Mark reviewed". Never reminded again. */
    REVIEWED,

    /** User tapped "Snooze". Reminded again once snoozeUntilEpochDay has passed. */
    SNOOZED,

    /** Backend pipeline failed; lastError explains why. Still shown so the user can retry. */
    FAILED,

    /** Soft-deleted by the user. Hidden everywhere. */
    DELETED,
    ;

    /**
     * Whether the daily 6pm reminder should count this card.
     *
     * A SNOOZED card only counts again once its snooze date has arrived, which is
     * why this needs [today] rather than being a plain property.
     */
    fun isDueForReminder(today: LocalDate): Boolean = when (this) {
        PENDING -> true
        SNOOZED -> true // caller additionally checks snoozeUntilEpochDay
        PROCESSING, QUEUED, REVIEWED, FAILED, DELETED -> false
    }

    /** Card is still actionable by the user. */
    val isActionable: Boolean
        get() = this == PENDING || this == SNOOZED || this == FAILED
}

/** A generated knowledge card. Deliberately holds no transcript or media. */
data class Card(
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
    val createdAt: Instant,
    val reviewedAt: Instant? = null,
    val snoozeUntilEpochDay: Long? = null,
    val providerName: String? = null,
    val thumbnailUrl: String? = null,
    val lastError: String? = null,
) {
    /** True when this card should be listed under the 6pm reminder. */
    fun isDueForReminder(today: LocalDate): Boolean = when (status) {
        CardStatus.SNOOZED -> (snoozeUntilEpochDay ?: Long.MIN_VALUE) <= today.toEpochDay()
        else -> status.isDueForReminder(today)
    }
}
