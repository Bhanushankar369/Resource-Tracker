package com.resourcetracker.ui.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.resourcetracker.R
import com.resourcetracker.core.ReelUrl

/**
 * Bottom sheet for saving a reel by hand, with the caption and creator attached.
 *
 * Instagram will not let any app read a reel's caption or its account without Meta
 * App Review, so the caption is the one piece of reel text the user can actually
 * supply. It is asked for here rather than hidden behind a disclosure: it is the
 * difference between a real card and an empty one, and on the 6pm review it is the
 * whole point of the card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddReelSheet(
    onDismiss: () -> Unit,
    onSubmit: (url: String, note: String?, description: String?, creator: String?) -> Unit,) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var creator by remember { mutableStateOf("") }

    val normalized = remember(url) { ReelUrl.normalize(ReelUrl.extract(url) ?: url) }
    val canSubmit = normalized != null

    // Pre-fill from a link pasted into the URL box, so a user copying a caption and
    // the link together only has to paste once.
    var filledFromUrl by remember(url) { mutableStateOf(false) }
    if (normalized != null && !filledFromUrl) {
        val caption = ReelUrl.captionFromSharedText(url)
        val handle = ReelUrl.creatorFromSharedText(url)
        if (caption.isNotBlank()) description = caption
        if (handle != null) creator = handle
        filledFromUrl = true
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.add_reel_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.add_reel_hint)) },
                singleLine = true,
                isError = url.isNotBlank() && normalized == null,
                supportingText = {
                    val platform = normalized?.let { ReelUrl.detectPlatform(it) }
                    val text = when {
                        url.isBlank() -> stringResource(R.string.add_reel_platform_hint)
                        normalized == null -> stringResource(R.string.add_reel_invalid)
                        platform == ReelUrl.Platform.YOUTUBE -> "YouTube link detected — metadata will be extracted"
                        platform == ReelUrl.Platform.X_TWITTER -> "X (Twitter) post detected — tweet content will be extracted"
                        platform == ReelUrl.Platform.INSTAGRAM -> "Instagram reel detected"
                        else -> "Web link detected"
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall)
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(R.string.add_reel_caption_hint)) },
                placeholder = { Text(stringResource(R.string.add_reel_caption_placeholder)) },
                minLines = 3,
                maxLines = 6,
                supportingText = {
                    Text(
                        stringResource(R.string.add_reel_caption_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = creator,
                onValueChange = { creator = it },
                label = { Text(stringResource(R.string.add_reel_creator_hint)) },
                placeholder = { Text(stringResource(R.string.add_reel_creator_placeholder)) },
                singleLine = true,
                leadingIcon = { Text("@", modifier = Modifier.padding(start = 12.dp)) },
                supportingText = {
                    Text(
                        stringResource(R.string.add_reel_creator_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.add_reel_note_hint)) },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(20.dp))

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.common_cancel))
            }

            androidx.compose.material3.Button(
                onClick = {
                    onSubmit(
                        requireNotNull(normalized),
                        note.takeIf { it.isNotBlank() },
                        description.takeIf { it.isNotBlank() },
                        creator.trim().removePrefix("@").takeIf { it.isNotBlank() },
                    )
                    onDismiss()
                },
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.add_reel_submit))
            }
        }
    }
}
