package com.resourcetracker.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.resourcetracker.domain.ReminderTime
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Non-identity preferences, kept apart from the session store because a sign-out
 * wipes the session but must not reset the reminder time.
 */
@Singleton
class SettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        /** Minutes since midnight at which the daily reminder fires. Defaults to 18:00. */
        val REMINDER_TIME_MINUTES = intPreferencesKey("reminder_time_minutes")

        /**
         * Epoch day of the last digest that was actually posted.
         *
         * Without this, the exact alarm and the periodic backstop can both fire on the
         * same evening and the user gets two identical notifications.
         */
        val LAST_REMINDER_EPOCH_DAY = longPreferencesKey("last_reminder_epoch_day")
    }

    /** The configured reminder time as minutes since midnight; never null (default 18:00). */
    val reminderTimeMinutes: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        (prefs[Keys.REMINDER_TIME_MINUTES] ?: ReminderTime.DEFAULT_MINUTES)
            .coerceIn(0, 23 * 60 + 59)
    }

    suspend fun currentReminderTimeMinutes(): Int = reminderTimeMinutes.first()

    suspend fun setReminderTimeMinutes(minutes: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.REMINDER_TIME_MINUTES] = minutes.coerceIn(0, 23 * 60 + 59)
        }
    }

    /** Epoch day the last digest was posted, or null if it never has been. */
    val lastReminderEpochDay: Flow<Long?> = context.settingsDataStore.data.map { prefs ->
        prefs[Keys.LAST_REMINDER_EPOCH_DAY]
    }

    suspend fun markReminderPosted(epochDay: Long) {
        context.settingsDataStore.edit { prefs -> prefs[Keys.LAST_REMINDER_EPOCH_DAY] = epochDay }
    }
}