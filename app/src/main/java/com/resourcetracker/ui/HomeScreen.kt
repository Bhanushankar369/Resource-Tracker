package com.resourcetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.resourcetracker.R
import com.resourcetracker.domain.model.Card
import com.resourcetracker.ui.cards.AddReelSheet
import com.resourcetracker.ui.cards.CardLists
import com.resourcetracker.ui.components.BackendOfflineBanner
import com.resourcetracker.ui.components.CardRow
import com.resourcetracker.ui.components.EmptyReviewedIcon
import com.resourcetracker.ui.components.EmptyReviewIcon
import com.resourcetracker.ui.components.EmptyState
import com.resourcetracker.ui.components.InFlightRow
import com.resourcetracker.ui.settings.SettingsScreen
import com.resourcetracker.ui.settings.SettingsUiState

enum class HomeTab { REVIEW, REVIEWED, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    lists: CardLists,
    settings: SettingsUiState,
    sharedText: String?,
    snackbarHostState: SnackbarHostState,
    onAddReel: (String, String?, String?, String?) -> Unit,
    onOpenCard: (String) -> Unit,
    onSync: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onScheduleReminder: () -> Unit,
    onSendTestReminder: () -> Unit,
    onSetReminderTime: (Int) -> Unit,
    startTab: HomeTab,
) {
    var tab by remember { mutableStateOf(startTab) }
    var showAddSheet by remember { mutableStateOf(false) }

    // The reminder notification asks the app to open the review tab. Only follow
    // that once, or returning to the app would keep yanking the user back here.
    LaunchedEffect(startTab) { tab = startTab }

    // A reel shared from Instagram is saved straight away rather than through the
    // sheet. The row is written to Room before this returns, so a confirmation step
    // would only stand between the user and a card they already asked for.
    //
    // Whatever text came along with the link is passed through: Instagram often
    // appends the caption to the shared text, and the repository also digs an
    // @handle out of it. Both go to the backend, which is what lets it build a real
    // card without Meta App Review. The sheet stays on the + button for when the
    // share carried only a URL and the caption has to be typed in.
    //
    // Keyed on the text and guarded on the last value, so a recomposition cannot
    // re-ingest the same reel, while a genuinely new share still goes through.
    var ingestedShare by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedText) {
        val incoming = sharedText?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (incoming == ingestedShare) return@LaunchedEffect
        ingestedShare = incoming
        onAddReel(incoming, null, null, null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (tab) {
                            HomeTab.REVIEW -> stringResource(R.string.tab_review)
                            HomeTab.REVIEWED -> stringResource(R.string.tab_reviewed)
                            HomeTab.SETTINGS -> stringResource(R.string.tab_settings)
                        },
                    )
                },
                actions = {
                    if (tab != HomeTab.SETTINGS) {
                        IconButton(onClick = onSync, enabled = !settings.isSyncing) {
                            Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.settings_sync_now))
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.REVIEW,
                    onClick = { tab = HomeTab.REVIEW },
                    icon = { Icon(Icons.Outlined.FactCheck, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_review)) },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.REVIEWED,
                    onClick = { tab = HomeTab.REVIEWED },
                    icon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_reviewed)) },
                )
                NavigationBarItem(
                    selected = tab == HomeTab.SETTINGS,
                    onClick = { tab = HomeTab.SETTINGS },
                    icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_settings)) },
                )
            }
        },
        floatingActionButton = {
            if (tab != HomeTab.SETTINGS) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_reel_title))
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                HomeTab.REVIEW -> ReviewTab(
                    lists = lists,
                    backend = settings.backend,
                    onOpenCard = onOpenCard,
                )

                HomeTab.REVIEWED -> ReviewedTab(
                    cards = lists.reviewed,
                    onOpenCard = onOpenCard,
                )

                HomeTab.SETTINGS -> SettingsScreen(
                    state = settings,
                    onSync = onSync,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    onScheduleReminder = onScheduleReminder,
                    onSendTestReminder = onSendTestReminder,
                    onSetReminderTime = onSetReminderTime,
                )
            }
        }
    }

    if (showAddSheet) {
        AddReelSheet(
            onDismiss = { showAddSheet = false },
            onSubmit = { url, note, description, creator ->
                onAddReel(url, note, description, creator)
            },
        )
    }
}

@Composable
private fun ReviewTab(
    lists: CardLists,
    backend: BackendState,
    onOpenCard: (String) -> Unit,
) {
    if (backend is BackendState.Unreachable) {
        Column(Modifier.fillMaxSize()) {
            BackendOfflineBanner(backend.reason)
            if (lists.due.isEmpty() && lists.inFlight.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.cards_empty_title),
                    body = stringResource(R.string.cards_empty_body),
                    icon = EmptyReviewIcon,
                )
            } else {
                CardList(lists.due, lists.inFlight, onOpenCard)
            }
        }
    } else if (lists.due.isEmpty() && lists.inFlight.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.cards_empty_title),
            body = stringResource(R.string.cards_empty_body),
            icon = EmptyReviewIcon,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        CardList(lists.due, lists.inFlight, onOpenCard)
    }
}

@Composable
private fun CardList(
    due: List<Card>,
    inFlight: List<Card>,
    onOpenCard: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(inFlight, key = { it.id }) { card ->
            InFlightRow(card)
        }
        items(due, key = { it.id }) { card ->
            CardRow(card = card, onClick = { onOpenCard(card.id) })
        }
    }
}

@Composable
private fun ReviewedTab(cards: List<Card>, onOpenCard: (String) -> Unit) {
    if (cards.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.reviewed_empty_title),
            body = stringResource(R.string.reviewed_empty_body),
            icon = EmptyReviewedIcon,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(cards, key = { it.id }) { card ->
                CardRow(card = card, onClick = { onOpenCard(card.id) })
            }
        }
    }
}
