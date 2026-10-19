package com.resourcetracker.data.local

import android.util.Log
import androidx.room.TypeConverter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Room converters for the list-of-strings columns on [CardEntity]. */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromStringList(value: List<String>): String = json.encodeToString(listSerializer, value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        try {
            json.decodeFromString(listSerializer, value)
        } catch (e: Exception) {
            // A corrupt column must not crash a read, but silently returning [] hides
            // the fact that a card just lost its insights/hashtags. Log it so the loss
            // is diagnosable instead of mysterious.
            Log.w(TAG, "Discarding a corrupt list column; falling back to an empty list", e)
            emptyList()
        }

    private companion object {
        const val TAG = "CardConverters"
    }
}
