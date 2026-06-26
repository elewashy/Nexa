package com.elewashy.nexa.feature.share.presentation

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.elewashy.nexa.core.display.RefreshRateManager
import com.elewashy.nexa.core.localization.AppLanguageManager
import com.elewashy.nexa.core.storage.AppPreferences
import com.elewashy.nexa.feature.share.domain.model.VideoQuality
import com.elewashy.nexa.ui.theme.NexaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lightweight transparent activity that handles ACTION_SEND intents.
 *
 * Displays only the quality-selection bottom sheet as an overlay on top of
 * whatever app the user shared from. The source app stays visible behind
 * this translucent window.
 *
 * Lifecycle:
 *  - Receives shared text from the intent
 *  - Shows the quality sheet via [ShareViewModel]
 *  - On download/cancel/error → finishes itself, returning to the source app
 */
@AndroidEntryPoint
class ShareActivity : AppCompatActivity() {

    private val viewModel: ShareViewModel by viewModels()

    @Inject lateinit var appPreferences: AppPreferences
    @Inject lateinit var refreshRateManager: RefreshRateManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText = intent
            ?.takeIf { it.action == Intent.ACTION_SEND && it.type == "text/plain" }
            ?.getStringExtra(Intent.EXTRA_TEXT)
        observeHighRefreshRate()
        observeAppLanguage()

        setContent {
            NexaTheme {
                ShareOverlay(
                    viewModel = viewModel,
                    sharedText = sharedText,
                    onClose = ::finishCleanly,
                )
            }
        }
    }

    private fun observeHighRefreshRate() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appPreferences.highRefreshRate
                    .distinctUntilChanged()
                    .collect { enabled -> refreshRateManager.apply(window, enabled) }
            }
        }
    }

    private fun observeAppLanguage() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appPreferences.languageTag
                    .distinctUntilChanged()
                    .collect(AppLanguageManager::setLanguageTag)
            }
        }
    }

    private fun finishCleanly(message: String?) {
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
        finish()
        @Suppress("DEPRECATION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(android.app.Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }
}

@Composable
private fun ShareOverlay(
    viewModel: ShareViewModel,
    sharedText: String?,
    onClose: (String?) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ShareEvent.Close -> onClose(event.message)
            }
        }
    }

    LaunchedEffect(sharedText) {
        viewModel.handleSharedText(sharedText)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.showSheet) {
            val audioQualities = remember(state.qualities) {
                state.qualities.filter { it.type == VideoQuality.MediaType.AUDIO }
            }
            val videoQualities = remember(state.qualities) {
                state.qualities.filter { it.type == VideoQuality.MediaType.VIDEO }
            }

            QualitySelectionSheet(
                platform = state.platform,
                audioQualities = audioQualities,
                videoQualities = videoQualities,
                isLoading = state.isLoading,
                sizeLoading = state.sizeLoading,
                onDownload = { quality ->
                    viewModel.onQualitySelected(quality)
                },
                onCancel = { onClose(null) },
            )
        }
    }
}
