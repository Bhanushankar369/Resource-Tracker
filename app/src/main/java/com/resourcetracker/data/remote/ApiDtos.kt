package com.resourcetracker.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire contract with the FastAPI backend.
 *
 * The backend owns the summary; these DTOs only ever carry a URL and a generated
 * card. Transcripts, audio, video and OCR frames never reach the device - the
 * backend deletes them before the job completes.
 */

@Serializable
data class HealthDto(
    @SerialName("status") val status: String,
    @SerialName("version") val version: String? = null,
    @SerialName("active_provider") val activeProvider: String? = null,
    @SerialName("providers") val providers: List<ProviderInfoDto> = emptyList(),
    @SerialName("llm_configured") val llmConfigured: Boolean? = null,
)

@Serializable
data class ProviderInfoDto(
    @SerialName("name") val name: String,
    @SerialName("available") val available: Boolean,
    @SerialName("description") val description: String? = null,
)

@Serializable
data class SubmitReelRequest(
    /**
     * The app generates the card id up front so the row can be inserted locally
     * (and shown in the UI) before any network call happens. The backend honours
     * it, which avoids a re-key dance when the job completes.
     */
    @SerialName("client_card_id") val clientCardId: String,
    @SerialName("reel_url") val reelUrl: String,
    /** Optional caption/text the user pasted alongside the reel. */
    @SerialName("note") val note: String? = null,
    /**
     * The reel's caption.
     *
     * Instagram's share sheet only sends the URL, and the public metadata endpoints
     * are not readable without Meta App Review, so this is normally the one real
     * source of reel text. It is filled from whatever text accompanied the shared
     * link, or typed by the user.
     */
    @SerialName("description") val description: String? = null,
    /** Who posted the reel. An Instagram reel URL does not contain the account. */
    @SerialName("creator") val creator: String? = null,
)

@Serializable
data class JobAcceptedDto(
    @SerialName("job_id") val jobId: String,
    @SerialName("card_id") val cardId: String,
    @SerialName("status") val status: String,
    @SerialName("provider") val provider: String? = null,
)

@Serializable
data class JobStatusDto(
    @SerialName("job_id") val jobId: String,
    @SerialName("card_id") val cardId: String,
    @SerialName("status") val status: String,
    @SerialName("stage") val stage: String? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("card") val card: CardDto? = null,
)

@Serializable
data class CardDto(
    @SerialName("id") val id: String,
    @SerialName("reel_url") val reelUrl: String,
    @SerialName("title") val title: String,
    @SerialName("summary") val summary: String,
    @SerialName("description") val description: String = "",
    @SerialName("hashtags") val hashtags: List<String> = emptyList(),
    @SerialName("creator_name") val creatorName: String? = null,
    @SerialName("creator_username") val creatorUsername: String? = null,
    @SerialName("creator_bio") val creatorBio: String? = null,
    @SerialName("key_insights") val keyInsights: List<String> = emptyList(),
    @SerialName("action_items") val actionItems: List<String> = emptyList(),
    @SerialName("topics") val topics: List<String> = emptyList(),
    @SerialName("status") val status: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("reviewed_at") val reviewedAt: String? = null,
    @SerialName("snooze_until_epoch_day") val snoozeUntilEpochDay: Long? = null,
    @SerialName("provider") val provider: String? = null,
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("last_error") val lastError: String? = null,
)

@Serializable
data class UpdateCardRequest(
    @SerialName("status") val status: String? = null,
    @SerialName("snooze_until_epoch_day") val snoozeUntilEpochDay: Long? = null,
)
