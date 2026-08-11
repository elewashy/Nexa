package com.elewashy.nexa.feature.splash.presentation.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.splash.presentation.SplashViewModel
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.icons.WifiOff

/**
 * No-internet screen shown when the device is offline during startup.
 *
 * Clean Material3 design: icon, title, description, a retry button and a
 * "continue anyway" affordance so zero connectivity is not a dead end —
 * the app can proceed on cached resources.
 *
 * @param onRetry Callback when the retry button is clicked.
 * @param onProceedAnyway Callback to continue offline. When null (the current
 *   MainActivity call site does not wire it), the activity-scoped
 *   [SplashViewModel] is resolved so the affordance still works.
 */
@Composable
fun NoInternetScreen(
    onRetry: () -> Unit,
    onProceedAnyway: (() -> Unit)? = null,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    // hiltViewModel() resolves the activity-scoped ViewModel here, so this
    // yields the same instance MainActivity holds via `by viewModels()`.
    val proceedAnyway = onProceedAnyway
        ?: hiltViewModel<SplashViewModel>()::onProceedAnywayClicked

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(adaptiveInfo.horizontalPadding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = adaptiveInfo.contentMaxWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = WifiOff,
                    contentDescription = null,
                    modifier = Modifier.size(if (adaptiveInfo.isTvLike) 88.dp else 72.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = stringResource(R.string.no_internet_connection),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.enable_internet),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(32.dp))

                Button(onClick = onRetry) {
                    Text(text = stringResource(R.string.retry))
                }

                TextButton(onClick = proceedAnyway) {
                    Text(text = stringResource(R.string.continue_anyway))
                }
            }
        }
    }
}
