package com.resourcetracker.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resourcetracker.BuildConfig
import com.resourcetracker.R
import com.resourcetracker.domain.ReminderTime
import com.resourcetracker.ui.BackendState
import com.resourcetracker.ui.components.formatEpochDay
import java.time.ZonedDateTime

/** Everything the settings tab renders, gathered so the composable stays declarative. */
data class SettingsUiState(
    val backend: BackendState,
    val isSyncing: Boolean,
    val notificationsAllowed: Boolean?,
    val canScheduleExactAlarms: Boolean,
    val isReminderScheduled: Boolean,
    val nextReminderAt: ZonedDateTime?,
    /** Minutes since midnight; fed from the same DataStore the scheduler reads. */
    val reminderTimeMinutes: Int,
    /**
     * The address this build was compiled with. Shown because "backend unreachable"
     * is almost always the wrong host rather than a stopped server - 127.0.0.1 on a
     * physical phone means the phone, and 10.0.2.2 only exists on an emulator.
     */
    val backendUrl: String,
)

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onSync: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onScheduleReminder: () -> Unit,
    onSendTestReminder: () -> Unit,
    onSetReminderTime: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        if (state.isSyncing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        BackendCard(state, onSync)
        ReminderCard(
            state,
            onRequestNotificationPermission,
            onOpenExactAlarmSettings,
            onScheduleReminder,
            onSendTestReminder,
            onSetReminderTime,
        )
        AboutCard()
    }
}

@Composable
private fun SectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun BackendCard(state: SettingsUiState, onSync: () -> Unit) {
    SectionCard(title = stringResource(R.string.settings_backend_title), icon = Icons.Filled.Info) {
        Text(
            text = BuildConfig.BACKEND_BASE_URL,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))

        when (val backend = state.backend) {
            is BackendState.Reachable -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(16.dp),
                    )
                    Text(
                        text = stringResource(
                            R.string.settings_provider,
                            backend.health.activeProvider
                                ?: stringResource(R.string.settings_provider_unknown),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                if (backend.health.providers.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Available: " + backend.health.providers.joinToString(", ") {
                            if (it.available) it.name else "${it.name} (off)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is BackendState.Unreachable -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Error,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.height(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_backend_offline, backend.reason.take(80)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            BackendState.Unknown -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = state.backendUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSync, enabled = !state.isSyncing) {
            Text(stringResource(R.string.settings_sync_now))
        }
    }
}

@Composable
private fun ReminderCard(
    state: SettingsUiState,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onScheduleReminder: () -> Unit,
    onSendTestReminder: () -> Unit,
    onSetReminderTime: (Int) -> Unit,
) {
    var showTimePicker by remember { mutableStateOf(false) }

    SectionCard(
        title = stringResource(R.string.settings_reminder_title),
        icon = Icons.Filled.Info,
    ) {
        Text(
            text = stringResource(
                R.string.settings_reminder_body,
                ReminderTime.labelFor(state.reminderTimeMinutes),
            ),
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            text = if (state.isReminderScheduled) {
                stringResource(R.string.settings_reminder_status_scheduled, describeNext(state))
            } else {
                stringResource(R.string.settings_reminder_status_unscheduled)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(
                    R.string.settings_reminder_time_value,
                    ReminderTime.labelFor(state.reminderTimeMinutes),
                ),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = { showTimePicker = true }) {
                Text(stringResource(R.string.settings_reminder_change_time))
            }
        }

        if (showTimePicker) {
            ReminderTimePickerDialog(
                initialMinutes = state.reminderTimeMinutes,
                onConfirm = { minutes ->
                    showTimePicker = false
                    onSetReminderTime(minutes)
                },
                onDismiss = { showTimePicker = false },
            )
        }

        // Notification permission is the difference between a reminder and nothing
        // happening, so it gets a row of its own.
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notifications_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (state.notificationsAllowed == false) {
                        stringResource(R.string.settings_notifications_blocked)
                    } else {
                        stringResource(R.string.settings_notifications_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.notificationsAllowed == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (state.notificationsAllowed == false) {
                TextButton(onClick = onRequestNotificationPermission) {
                    Text(stringResource(R.string.settings_allow_notifications))
                }
            }
        }

        if (!state.canScheduleExactAlarms) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_reminder_inexact),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onOpenExactAlarmSettings) {
                Text(stringResource(R.string.settings_grant_exact_alarm))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = onScheduleReminder) {                Text(if (state.isReminderScheduled) "Re-schedule" else "Enable reminder")
            }
            TextButton(onClick = onSendTestReminder) {
                Text(stringResource(R.string.settings_test_reminder))
            }
        }
    }
}

private fun describeNext(state: SettingsUiState): String =
    state.nextReminderAt?.let { next ->
        // formatEpochDay keeps this consistent with the rest of the app.
        "${formatEpochDay(next.toLocalDate().toEpochDay())} at ${ReminderTime.labelFor(state.reminderTimeMinutes)}"
    } ?: ReminderTime.labelFor(state.reminderTimeMinutes)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimePickerDialog(
    initialMinutes: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val timeState = rememberTimePickerState(
        initialHour = initialMinutes / 60,
        initialMinute = initialMinutes % 60,
        is24Hour = is24Hour,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_time_dialog_title)) },
        text = { TimePicker(state = timeState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(timeState.hour * 60 + timeState.minute) }) {
                Text(stringResource(R.string.reminder_time_dialog_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}

@Composable
private fun AboutCard() {
    SectionCard(title = stringResource(R.string.settings_about_title), icon = Icons.Filled.Info) {
        Text(
            text = stringResource(R.string.settings_about_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.settings_environment, BuildConfig.APP_ENV),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
