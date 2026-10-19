package com.resourcetracker.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.resourcetracker.BuildConfig
import com.resourcetracker.ui.cards.CardDetailScreen
import com.resourcetracker.ui.cards.CardDetailViewModel
import com.resourcetracker.ui.cards.CardsViewModel
import com.resourcetracker.ui.settings.SettingsUiState

object Routes {
    const val HOME = "home?startTab={startTab}"
    const val CARD_DETAIL = "card/{cardId}"

    fun home(startTab: HomeTab = HomeTab.REVIEW): String = "home?startTab=${startTab.name}"
    fun cardDetail(cardId: String): String = "card/$cardId"
}

/**
 * Root composable: the home graph.
 *
 * [sharedText] is the text Instagram sent via ACTION_SEND. It is latched into
 * [pendingSharedText] and the source is cleared immediately, so the same reel shared
 * twice is two events rather than one deduplicated value.
 */
@Composable
fun ResourceTrackerRoot(
    sharedText: String?,
    startTab: HomeTab,
    onStartTabConsumed: () -> Unit,
    onSharedTextConsumed: () -> Unit,
    appViewModel: AppViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val backend by appViewModel.backend.collectAsStateWithLifecycle()
    val notificationsAllowed by appViewModel.notificationsAllowed.collectAsStateWithLifecycle()
    val isSyncing by appViewModel.syncing.collectAsStateWithLifecycle()
    val reminderTimeMinutes by appViewModel.reminderTimeMinutes.collectAsStateWithLifecycle()
    val reminderScheduled by appViewModel.reminderScheduled.collectAsStateWithLifecycle()
    val nextReminderAt by appViewModel.nextReminderAt.collectAsStateWithLifecycle()

    var pendingSharedText by remember { mutableStateOf<String?>(null) }

    // Latching in an effect rather than writing state during composition: a write in
    // composition schedules a recomposition that can replace the LaunchedEffect below
    // before it ever runs, and the shared reel is then silently dropped.
    LaunchedEffect(sharedText) {
        val incoming = sharedText?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        pendingSharedText = incoming
        onSharedTextConsumed()
    }
val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> appViewModel.onNotificationPermissionResult(granted) }

    // The first saved reel is when notifications become worth having, so the system
    // dialog is shown then rather than being buried in Settings. Without this the 6pm
    // digest silently never arrives, because POST_NOTIFICATIONS is never granted.
    val notificationPrompt by appViewModel.notificationPermissionPrompt
        .collectAsStateWithLifecycle()
    LaunchedEffect(notificationPrompt) {
        if (notificationPrompt > 0) {
            requestNotificationPermission(context, notificationPermissionLauncher)
        }
    }

    LaunchedEffect(Unit) {
        appViewModel.messages.collect { message -> snackbarHostState.showSnackbar(message.text) }
    }

    // The reminder notification asks the app to open the review tab. Consume that
    // request once, otherwise returning to the app would keep forcing the tab.
    LaunchedEffect(startTab) {
        if (startTab != HomeTab.REVIEW) onStartTabConsumed()
    }

    val settingsState = SettingsUiState(
        backend = backend,
        isSyncing = isSyncing,
        notificationsAllowed = notificationsAllowed,
        canScheduleExactAlarms = appViewModel.canScheduleExactAlarms,
        isReminderScheduled = reminderScheduled,
        nextReminderAt = nextReminderAt,
        reminderTimeMinutes = reminderTimeMinutes,
        backendUrl = BuildConfig.BACKEND_BASE_URL,
    )

    val cardsViewModel: CardsViewModel = hiltViewModel()
    val lists by cardsViewModel.lists.collectAsStateWithLifecycle()

    NavHost(
        navController = navController,
        startDestination = Routes.home(startTab),
    ) {
        composable(
            route = Routes.HOME,
            arguments = listOf(
                navArgument("startTab") {
                    type = NavType.StringType
                    defaultValue = HomeTab.REVIEW.name
                },
            ),
        ) { entry ->
            val tab = runCatching {
                HomeTab.valueOf(
                    entry.arguments?.getString("startTab") ?: HomeTab.REVIEW.name,
                )
            }.getOrDefault(HomeTab.REVIEW)

            HomeScreen(
                lists = lists,
                settings = settingsState,
                sharedText = pendingSharedText,
                snackbarHostState = snackbarHostState,
                startTab = tab,
                onAddReel = { url, note, description, creator ->
                    appViewModel.addReel(url, note, description, creator)
                },
                onOpenCard = { id -> navController.navigate(Routes.cardDetail(id)) },
                onSync = { appViewModel.syncNow() },
                onRequestNotificationPermission = {
                    requestNotificationPermission(context, notificationPermissionLauncher)
                },
                onOpenExactAlarmSettings = { openExactAlarmSettings(context) },
                onScheduleReminder = { appViewModel.scheduleReminder() },
                onSendTestReminder = { appViewModel.sendTestReminder() },
                onSetReminderTime = { minutes -> appViewModel.setReminderTimeMinutes(minutes) },
            )
        }

        composable(
            route = Routes.CARD_DETAIL,
            arguments = listOf(navArgument("cardId") { type = NavType.StringType }),
        ) { entry ->
            val detailViewModel: CardDetailViewModel = hiltViewModel(entry)
            val cardState by detailViewModel.card.collectAsStateWithLifecycle()

            CardDetailScreen(
                state = cardState,
                onBack = { navController.popBackStack() },
                onMarkReviewed = { appViewModel.markReviewed(detailViewModel.id) },
                onSnooze = { appViewModel.snooze(detailViewModel.id) },
                onDelete = { appViewModel.delete(detailViewModel.id) },
                onRetry = { appViewModel.retry(detailViewModel.id) },
            )
        }
    }
}

private fun requestNotificationPermission(
    context: Context,
    launcher: androidx.activity.result.ActivityResultLauncher<String>,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        // Pre-13 there is no runtime permission, so the only lever is app settings.
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
        )
    }
}

/**
 * Opens the screen where the user grants "Alarms & reminders" access.
 *
 * ACTION_REQUEST_SCHEDULE_EXACT_ALARM is the documented route, but some OEM builds
 * do not resolve it, hence the fallback to the app details page.
 */
private fun openExactAlarmSettings(context: Context) {
    // The constant and the activity only exist from API 31. Referencing the field on an
    // older device is a NoSuchFieldError, not a resolvable intent, so the version check
    // has to happen before the Intent is built.
    val canRequestDirectly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val launched = if (canRequestDirectly) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    Uri.fromParts("package", context.packageName, null),
                ),
            )
            true
        }.getOrDefault(false)
    } else {
        false
    }

    if (!launched) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
            },
        )
    }
}
