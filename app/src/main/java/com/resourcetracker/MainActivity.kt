package com.resourcetracker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.work.WorkManager
import com.resourcetracker.ui.HomeTab
import com.resourcetracker.ui.ResourceTrackerRoot
import com.resourcetracker.ui.theme.ResourceTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The share target.
 *
 * Instagram's share sheet sends `ACTION_SEND` with `text/plain` containing the reel
 * URL buried inside caption text. That is the only ingestion path that works on a
 * real device - see the README for why a reel URL cannot be turned into a video
 * on-device without a backend.
 *
 * The POST_NOTIFICATIONS permission is deliberately NOT requested here. A cold-start
 * permission prompt with no explanation gets denied; the Settings tab asks in
 * context, next to the reminder feature that needs it.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val sharedText = MutableStateFlow<String?>(null)
    private val startTab = MutableStateFlow(HomeTab.REVIEW)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        consumeIntent(intent)

        // Touch WorkManager once so on-demand initialisation picks up the Hilt worker
        // factory from the Application before any job is enqueued.
        WorkManager.getInstance(this)

        setContent {
            // Collected as state, not read via .value. Reading .value inside composition
            // would never recompose when an intent arrives.
            val incomingSharedText by sharedText.collectAsState()
            val requestedTab by startTab.collectAsState()

            ResourceTrackerTheme {
                ResourceTrackerRoot(
                    sharedText = incomingSharedText,
                    startTab = requestedTab,
                    onStartTabConsumed = { startTab.value = HomeTab.REVIEW },
                    onSharedTextConsumed = { sharedText.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    /**
     * Reads the incoming intent.
     *
     * Two cases matter: the reminder notification (open the review tab) and an
     * Instagram share (prefill the add sheet).
     */
    private fun consumeIntent(intent: Intent?) {
        if (intent == null) return

        if (intent.getBooleanExtra(EXTRA_OPEN_TAB_PENDING, false)) {
            startTab.value = HomeTab.REVIEW
            // Remove it so a configuration change does not re-trigger the tab.
            intent.removeExtra(EXTRA_OPEN_TAB_PENDING)
        }

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type?.startsWith("text/") == true) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { sharedText.value = it }
                }
            }

            else -> Unit
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB_PENDING = "open_tab_pending"
    }
}
