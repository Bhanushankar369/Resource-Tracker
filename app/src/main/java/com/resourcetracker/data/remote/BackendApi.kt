package com.resourcetracker.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Backend surface.
 *
 * The app is a thin client: it submits reel URLs and reads back generated cards.
 * All media/transcript/LLM work happens server side.
 */
interface BackendApi {

    @GET("v1/health")
    suspend fun health(): HealthDto

    /** Queue a reel for processing. Returns 202 with the job id. */
    @POST("v1/reels")
    suspend fun submitReel(@Body body: SubmitReelRequest): JobAcceptedDto

    @GET("v1/jobs/{jobId}")
    suspend fun jobStatus(@Path("jobId") jobId: String): JobStatusDto

    @GET("v1/cards")
    suspend fun listCards(
        @Query("status") status: String? = null,
        @Query("limit") limit: Int = 200,
    ): List<CardDto>

    @PATCH("v1/cards/{id}")
    suspend fun updateCard(
        @Path("id") id: String,
        @Body body: UpdateCardRequest,
    ): Response<CardDto>

    @DELETE("v1/cards/{id}")
    suspend fun deleteCard(@Path("id") id: String): Response<Unit>
}
