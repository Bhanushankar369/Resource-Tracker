package com.resourcetracker.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.resourcetracker.R
import com.resourcetracker.data.remote.BackendApi
import com.resourcetracker.data.remote.HealthDto
import com.resourcetracker.data.repository.AddReelResult
import com.resourcetracker.data.repository.CardRepository
import com.resourcetracker.data.settings.SettingsStore
import com.resourcetracker.domain.ReminderTime
import com.resourcetracker.reminder.DailyReminderScheduler
import com.resourcetracker.reminder.ReminderNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.ZonedDateTime
import javax.inject.Inject

/** One-shot message for a snackbar. */
data class UiMessage(val text: String)

sealed interface BackendState {
    data object Unknown : BackendState
    data class Reachable(val health: HealthDto) : BackendState
    data class Unreachable(val reason: String) : BackendState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cardRepository: CardRepository,
    private val reminderScheduler: DailyReminderScheduler,
    private val reminderNotifier: ReminderNotifier,
    private val api: BackendApi,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _backend = MutableStateFlow<BackendState>(BackendState.Unknown)
    val backend: StateFlow<BackendState> = _backend.asStateFlow()

    private val _notificationsAllowed =
        MutableStateFlow<Boolean?>(context.canPostNotifications())
    val notificationsAllowed: StateFlow<Boolean?> = _notificationsAllowed.asStateFlow()

    /**
     * Increments to ask the UI to show the POST_NOTIFICATIONS system dialog.
     *
     * The 6pm digest is the product's headline promise and it is silently dropped if
     * this permission is never granted - the worker logs a warning nobody sees and the
     * user just concludes the alarm is broken. Asking in Settings was enough to miss
     * it, so the first time a reel is actually saved the app asks directly, which is
     * also the moment the reason for wanting notifications is obvious.
     */
    private val _notificationPermissionPrompt = MutableStateFlow(0)
    val notificationPermissionPrompt: StateFlow<Int> = _notificationPermissionPrompt.asStateFlow()

    /** Android only ever shows the system dialog once, so do not keep knocking. */
    private var hasPromptedForNotifications = false

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** The configured reminder time (minutes since midnight), so the Settings screen and
     *  the picker can react to changes without another DataStore read. */
    private val _reminderTimeMinutes = MutableStateFlow(ReminderTime.DEFAULT_MINUTES)
    val reminderTimeMinutes: StateFlow<Int> = _reminderTimeMinutes.asStateFlow()

    /**
     * Whether a daily alarm is registered, and when it will next fire.
     *
     * These used to be plain getters reading [reminderScheduler]. A getter is not
     * observable, so tapping "Enable reminder" scheduled the alarm but left the button
     * showing "Enable reminder" - the value was never re-read because no state changed.
     */
    private val _reminderScheduled = MutableStateFlow(reminderScheduler.isScheduled())
    val reminderScheduled: StateFlow<Boolean> = _reminderScheduled.asStateFlow()

    private val _nextReminderAt = MutableStateFlow(reminderScheduler.nextTriggerAt())
    val nextReminderAt: StateFlow<ZonedDateTime?> = _nextReminderAt.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    val canScheduleExactAlarms: Boolean get() = reminderScheduler.canScheduleExactAlarms

    init {
        refreshBackend()
        viewModelScope.launch {
            settingsStore.reminderTimeMinutes.collect {
                _reminderTimeMinutes.value = it
                refreshReminderState()
            }
        }
        // (Re)arm the daily alarm on every app start.
        //
        // It used to be armed only when the user changed the time in Settings, on
        // boot, or by the alarm re-arming itself after firing. That left the common
        // case with no alarm at all: install the app, never open Settings, and the
        // 6pm reminder simply never arrives. schedule() replaces the PendingIntent,
        // so calling it on each start is idempotent and self-healing after the OS
        // drops the alarm.
        viewModelScope.launch {
            reminderScheduler.schedule()
            refreshReminderState()
        }
    }

    private fun refreshReminderState() {
        _reminderScheduled.value = reminderScheduler.isScheduled()
        _nextReminderAt.value = reminderScheduler.nextTriggerAt(_reminderTimeMinutes.value)
    }

    // ------------------------------------------------------------------ cards

    /**
     * Saves a reel. The row is written to Room before this returns, so the card shows
     * up in the list immediately; the upload happens in the background.
     */
    fun addReel(
        sharedTextOrUrl: String,
        note: String?,
        description: String? = null,
        creator: String? = null,
    ) {
        viewModelScope.launch {
            when (cardRepository.addReel(sharedTextOrUrl, note, description, creator)) {
                is AddReelResult.Accepted -> {
                    emit(stringRes = R.string.add_reel_saved)
                    // Now there is something to remind about, so this is the moment to
                    // ask for notifications rather than in a Settings screen the user
                    // may never open.
                    if (notificationsAllowed.value == false && !hasPromptedForNotifications) {
                        hasPromptedForNotifications = true
                        _notificationPermissionPrompt.value += 1
                    }
                }
                is AddReelResult.Duplicate -> emit(stringRes = R.string.add_reel_duplicate)
                is AddReelResult.NotAUrl -> emit(stringRes = R.string.add_reel_invalid)
            }
        }
    }

    fun markReviewed(id: String) = viewModelScope.launch {
        cardRepository.markReviewed(id)
        emit(stringRes = R.string.card_reviewed_toast)
    }

    fun snooze(id: String) = viewModelScope.launch {
        cardRepository.snooze(id)
        emit(stringRes = R.string.card_snoozed_toast)
    }

    fun delete(id: String) = viewModelScope.launch {
        cardRepository.delete(id)
        emit(stringRes = R.string.card_deleted)
    }

    fun retry(id: String) = viewModelScope.launch { cardRepository.retry(id) }

    // ------------------------------------------------------------------- sync

    fun refreshBackend() {
        viewModelScope.launch {
            _backend.value = try {
                BackendState.Reachable(api.health())
            } catch (e: IOException) {
                BackendState.Unreachable(e.message ?: "network error")
            } catch (e: Exception) {
                BackendState.Unreachable(e.message ?: "unexpected error")
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncing.value = true
            val ok = runCatching { cardRepository.sync() }.isSuccess
            _syncing.value = false
            emit(stringRes = if (ok) R.string.settings_synced else R.string.settings_sync_failed)
        }
    }

    // -------------------------------------------------------------- reminders

    fun scheduleReminder() = viewModelScope.launch {
        reminderScheduler.schedule()
        refreshReminderState()
    }

    fun sendTestReminder() {
        viewModelScope.launch {
            val posted = reminderNotifier.notifyTestReminder()
            emit(
                stringRes = if (posted) R.string.settings_test_reminder_sent
                else R.string.settings_test_reminder_blocked,
            )
        }
    }

    /** Persists a new reminder time and re-arms the alarm so the change applies at once. */
    fun setReminderTimeMinutes(minutes: Int) {
        viewModelScope.launch {
            settingsStore.setReminderTimeMinutes(minutes)
            reminderScheduler.schedule()
            refreshReminderState()
        }
    }

    /** Reported by the activity after the POST_NOTIFICATIONS permission dialog. */
    fun onNotificationPermissionResult(granted: Boolean) {
        _notificationsAllowed.value = granted
    }

    private fun emit(stringRes: Int) = emit(context.getString(stringRes))

    private fun emit(text: String) {
        viewModelScope.launch { _messages.send(UiMessage(text)) }
    }
}

/**
 * Whether notifications can currently be posted.
 *
 * Read once at construction so the very first save already knows whether to prompt,
 * rather than sitting on `null` until the user happens to visit Settings.
 */
private fun Context.canPostNotifications(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        NotificationManagerCompat.from(this).areNotificationsEnabled()
    }
