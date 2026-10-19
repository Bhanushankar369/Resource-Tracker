package com.resourcetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.resourcetracker.domain.model.Card
import com.resourcetracker.domain.model.CardStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Status pill shown on every card row. */
@Composable
fun StatusChip(status: CardStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        CardStatus.QUEUED -> "Queued"
        CardStatus.PROCESSING -> "Summarising"
        CardStatus.PENDING -> "To review"
        CardStatus.REVIEWED -> "Reviewed"
        CardStatus.SNOOZED -> "Snoozed"
        CardStatus.FAILED -> "Failed"
        CardStatus.DELETED -> "Deleted"
    }

    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** One card in a list. */
@Composable
fun CardRow(
    card: Card,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    CreatorLine(card)
                }
                Thumbnail(card)
                StatusChip(card.status)
            }

            when (card.status) {
                CardStatus.QUEUED, CardStatus.PROCESSING -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                CardStatus.FAILED -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = card.lastError ?: "Could not summarise this reel",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                CardStatus.DELETED -> Unit

                else -> {
                    val preview = card.description.ifBlank { card.summary }
                    if (preview.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            HashtagRow(card.hashtags, Modifier.padding(top = 8.dp))
            TopicRow(card.topics, Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * The reel's cover image, when a provider managed to read it.
 *
 * Instagram's thumbnail URLs carry an expiry, so a card synced back weeks later can
 * 404. That is why there is no error state here beyond rendering nothing: a broken
 * image must never take the row with it.
 */
@Composable
private fun Thumbnail(card: Card) {
    val url = card.thumbnailUrl?.takeIf { it.isNotBlank() } ?: return
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(52.dp)
            .clip(RoundedCornerShape(8.dp)),
    )
}

@Composable
private fun CreatorLine(card: Card) {
    val handle = card.creatorUsername?.let { "@$it" }
    val name = card.creatorName
    val text = when {
        handle != null && name != null -> "$name ($handle)"
        handle != null -> handle
        name != null -> name
        else -> null
    }
    if (text != null) {
        Spacer(Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun HashtagRow(tags: List<String>, modifier: Modifier = Modifier, maxTags: Int = 6) {
    if (tags.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.take(maxTags).forEach { tag ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(text = "#$tag", style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.LocalOffer,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    disabledLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            )
        }
    }
}

@Composable
fun TopicRow(topics: List<String>, modifier: Modifier = Modifier) {
    if (topics.isEmpty()) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        topics.take(3).forEach { topic ->
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(text = topic, style = MaterialTheme.typography.labelSmall) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(AssistChipDefaults.IconSize),
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledLeadingIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    body: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Banner shown at the top of the review tab when the backend cannot be reached. */
@Composable
fun BackendOfflineBanner(reason: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    text = "Backend unreachable",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = reason.take(160),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

/** Placeholder row for a card that is still being summarised. */
@Composable
fun InFlightRow(card: Card, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.HourglassEmpty,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            Text(
                text = card.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            )
        }
    }
}

/**
 * Formatter built per call rather than held in a static field: a field would capture
 * the locale that was active at class-load time, so a runtime locale change would
 * leave dates formatted the old way.
 */
internal fun formatEpochDay(epochDay: Long?): String = epochDay?.let {
    LocalDate.ofEpochDay(it)
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
} ?: ""

internal fun formatInstant(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))

/** Icon for the empty states of the two list tabs. */
val EmptyReviewIcon: ImageVector get() = Icons.Filled.HourglassEmpty
val EmptyReviewedIcon: ImageVector get() = Icons.Outlined.CheckCircle
