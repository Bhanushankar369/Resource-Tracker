package com.resourcetracker.ui.cards

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.resourcetracker.R
import com.resourcetracker.core.ReelUrl
import com.resourcetracker.domain.model.Card
import com.resourcetracker.domain.model.CardStatus
import com.resourcetracker.ui.components.HashtagRow
import com.resourcetracker.ui.components.StatusChip
import com.resourcetracker.ui.components.formatEpochDay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardDetailScreen(
    state: CardState,
    onBack: () -> Unit,
    onMarkReviewed: () -> Unit,
    onSnooze: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        when (state) {
            CardState.Loading -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) { CircularProgressIndicator() }

            CardState.Missing -> Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(24.dp),
            ) {
                Text(stringResource(R.string.card_not_found), style = MaterialTheme.typography.titleMedium)
            }

            is CardState.Loaded -> CardDetailContent(
                card = state.card,
                onBack = onBack,
                onMarkReviewed = onMarkReviewed,
                onSnooze = onSnooze,
                onDelete = onDelete,
                onRetry = onRetry,
                modifier = Modifier.padding(padding),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardDetailContent(
    card: Card,
    onBack: () -> Unit,
    onMarkReviewed: () -> Unit,
    onSnooze: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = card.title,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusChip(card.status)
            card.providerName?.let {
                Text(
                    text = "via $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (card.status == CardStatus.SNOOZED) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.card_snoozed_until, formatEpochDay(card.snoozeUntilEpochDay)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (card.status == CardStatus.REVIEWED && card.reviewedAt != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.card_reviewed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(14.dp))

        CreatorProfileBlock(card)

        Spacer(Modifier.height(16.dp))

        when (card.status) {
            CardStatus.QUEUED, CardStatus.PROCESSING -> {
                Text(
                    text = stringResource(R.string.card_processing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            CardStatus.FAILED -> {
                Text(
                    text = card.lastError ?: stringResource(R.string.card_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRetry) {
                    Text(stringResource(R.string.card_retry))
                }
            }

            else -> {
                if (card.description.isNotBlank()) {
                    SectionHeading(stringResource(R.string.card_about_heading))
                    Text(
                        text = card.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (card.summary.isNotBlank()) {
                    SectionHeading(stringResource(R.string.card_summary_heading))
                    Text(text = card.summary, style = MaterialTheme.typography.bodyMedium)
                }

                if (card.keyInsights.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.card_insights_heading))
                    BulletList(card.keyInsights)
                }

                if (card.actionItems.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.card_actions_heading))
                    BulletList(card.actionItems)
                }

                if (card.hashtags.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.card_hashtags_heading))
                    HashtagRow(card.hashtags, maxTags = 20)
                }

                if (card.topics.isNotEmpty()) {
                    SectionHeading(stringResource(R.string.card_topics_heading))
                    Text(
                        text = card.topics.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Text(
            text = card.reelUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        val platform = remember(card.reelUrl) { ReelUrl.detectPlatform(card.reelUrl) }
        val openText = when (platform) {
            ReelUrl.Platform.YOUTUBE -> stringResource(R.string.card_open_youtube)
            ReelUrl.Platform.X_TWITTER -> stringResource(R.string.card_open_x)
            ReelUrl.Platform.INSTAGRAM -> stringResource(R.string.card_open_reel)
            ReelUrl.Platform.UNKNOWN -> stringResource(R.string.card_open_generic)
        }
        OutlinedButton(
            onClick = { openReel(context, card.reelUrl) },
            enabled = card.reelUrl.isNotBlank(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                modifier = Modifier.height(18.dp),
            )
            Text(
                text = openText,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        // The three MVP actions. Review is the primary one, so it gets the filled
        // button; snooze and delete are secondary because both are recoverable-ish.
        if (card.status.isActionable) {
            Button(
                onClick = {
                    onMarkReviewed()
                    onBack()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Text(
                    text = stringResource(R.string.card_mark_reviewed),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onSnooze, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Filled.Snooze,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.card_snooze),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.height(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.common_delete),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }
        } else if (card.status == CardStatus.REVIEWED) {
            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.common_back))
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.card_delete_confirm_title)) },
            text = { Text(stringResource(R.string.card_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                        onBack()
                    },
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Spacer(Modifier.height(18.dp))
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
}

/** The reel creator's public profile, when the backend could read it. */
@Composable
private fun CreatorProfileBlock(card: Card) {
    val handle = card.creatorUsername?.let { "@$it" }
    val display = when {
        handle != null && !card.creatorName.isNullOrBlank() -> "$card.creatorName ($handle)"
        handle != null -> handle
        !card.creatorName.isNullOrBlank() -> card.creatorName
        else -> null
    }
    if (display == null && card.creatorBio.isNullOrBlank()) return

    Column {
        display?.let {
            Text(text = it, style = MaterialTheme.typography.titleSmall)
        }
        if (!card.creatorBio.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = card.creatorBio,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BulletList(items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Row {
                Text(text = "•", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = item,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * Opens the reel in whatever app handles web links, without crashing.
 *
 * Two things can go wrong: the stored URL may not be http(s) (a malformed or
 * server-supplied value), and no activity may be installed to handle ACTION_VIEW.
 * The old code called `startActivity` directly, so either case was an
 * ActivityNotFoundException that took the screen down.
 */
private fun openReel(context: Context, reelUrl: String) {
    val uri = runCatching { Uri.parse(reelUrl) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    if (uri == null || (scheme != "http" && scheme != "https")) {
        Toast.makeText(context, R.string.card_open_reel_failed, Toast.LENGTH_SHORT).show()
        return
    }
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.card_open_reel_failed, Toast.LENGTH_SHORT).show()
    }
}
