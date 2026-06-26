package com.elewashy.nexa.feature.onboarding

import android.annotation.SuppressLint
import android.Manifest
import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.settings.presentation.settings.CustomizeThemeScreen
import com.elewashy.nexa.feature.settings.presentation.settings.SettingsViewModel
import com.elewashy.nexa.ui.icons.ArrowForwardFilled
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.FolderOpen
import com.elewashy.nexa.ui.icons.Notifications
import com.elewashy.nexa.ui.icons.Security

/**
 * First-launch onboarding screen that requests required permissions.
 *
 * Exact replica of the reference project's onboarding Permissions step:
 * - Welcome header with app icon + app name
 * - Scrollable permission list in a segmented-list section
 * - Step title at the top and step description at the bottom of the
 *   scrollable area (portrait) or beside it (landscape split layout)
 * - Bottom bar with Skip (secondary) and Continue (primary) buttons
 * - Skip confirmation AlertDialog
 *
 * @param onFinish Called when the user completes or skips onboarding.
 * @param vm The onboarding ViewModel.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(
    onFinish: () -> Unit,
    vm: OnboardingViewModel,
    settingsViewModel: SettingsViewModel,
) {
    val context = LocalContext.current
    var showSkipDialog by remember { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(OnboardingStep.Permissions) }

    if (step == OnboardingStep.Theme) {
        CustomizeThemeScreen(
            onBackClick = { step = OnboardingStep.Permissions },
            viewModel = settingsViewModel,
            bottomBar = {
                Box(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Button(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        onClick = onFinish,
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(text = stringResource(R.string.next))
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = ArrowForwardFilled,
                            contentDescription = null,
                        )
                    }
                }
            },
        )
        return
    }

    // --- Permission launchers ---

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.refreshPermissionStates()
    }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        vm.refreshPermissionStates()
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        vm.refreshPermissionStates()
    }

    val installAppsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        vm.refreshPermissionStates()
    }

    // --- Strings ---

    val stepTitle = stringResource(R.string.onboarding_permissions_subtitle)
    val stepDescription = stringResource(R.string.onboarding_permissions_skip_description)

    // --- Permission content (shared between portrait / landscape) ---

    val permissionsContent: @Composable ColumnScope.() -> Unit = {
        // Storage
        PermissionItem(
            icon = FolderOpen,
            title = stringResource(R.string.permission_storage),
            description = stringResource(R.string.permission_storage_description),
            isGranted = vm.hasStoragePermission,
            onRequest = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try {
                        storagePermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                "package:${context.packageName}".toUri()
                            )
                        )
                    } catch (_: Exception) {
                        storagePermissionLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }
                } else {
                    legacyStoragePermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                        )
                    )
                }
            }
        )

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionItem(
                icon = Notifications,
                title = stringResource(R.string.permission_notifications),
                description = stringResource(R.string.permission_notifications_description),
                isGranted = vm.isNotificationsEnabled,
                onRequest = {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            )
        }

        // Install apps
        PermissionItem(
            icon = Security,
            title = stringResource(R.string.permission_install_apps),
            description = stringResource(R.string.permission_install_apps_description),
            isGranted = vm.canInstallApps,
            onRequest = {
                installAppsLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${context.packageName}".toUri()
                    )
                )
            }
        )
    }

    // --- Bottom buttons ---

    val onboardingButtons: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (!vm.allPermissionsGranted) {
                TextButton(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    onClick = { showSkipDialog = true },
                    shapes = ButtonDefaults.shapes()
                ) {
                    Text(text = stringResource(R.string.onboarding_skip))
                }
            }

            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                onClick = { step = OnboardingStep.Theme },
                enabled = vm.allPermissionsGranted,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(text = stringResource(R.string.next))
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = ArrowForwardFilled,
                    contentDescription = null
                )
            }
        }
    }

    // --- Scaffold ---

    Scaffold { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            val useSplitLayout = maxWidth >= 600.dp && maxWidth >= maxHeight
            val horizontalPadding = if (maxWidth < 360.dp) 12.dp else 16.dp

            if (useSplitLayout) {
                // Landscape: header + details on the left, permissions + buttons on the right
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        ColumnWithScrollbarEdgeShadow(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    start = 16.dp,
                                    end = 12.dp,
                                    top = 24.dp,
                                    bottom = 24.dp
                                ),
                            verticalArrangement = Arrangement.spacedBy(24.dp)
                        ) {
                            OnboardingHeader()
                            StepDetails(title = stepTitle, description = stepDescription)
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(vertical = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        // Permissions list (scrollable, weighted)
                        ColumnWithScrollbarEdgeShadow(
                            modifier = Modifier
                                .padding(vertical = 16.dp)
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Segmented permission items
                            Column(
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.large),
                                verticalArrangement = Arrangement.spacedBy(
                                    ListItemDefaults.SegmentedGap
                                )
                            ) {
                                permissionsContent()
                            }
                        }
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            onboardingButtons()
                        }
                    }
                }
            } else {
                // Portrait: single column
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontalPadding, 24.dp),
                ) {
                    OnboardingHeader()

                    // Scrollable content area (weighted to push buttons to bottom)
                    ColumnWithScrollbarEdgeShadow(
                        modifier = Modifier
                            .padding(vertical = 16.dp)
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Step title
                        StepTitle(stepTitle)

                        // Segmented permission items
                        Column(
                            modifier = Modifier
                                .clip(MaterialTheme.shapes.large),
                            verticalArrangement = Arrangement.spacedBy(
                                ListItemDefaults.SegmentedGap
                            )
                        ) {
                            permissionsContent()
                        }

                        // Step description at the bottom of the scrollable area
                        StepDescription(stepDescription)
                    }

                    onboardingButtons()
                }
            }
        }

        // Skip permissions dialog
        if (showSkipDialog) {
            AlertDialog(
                onDismissRequest = { showSkipDialog = false },
                title = { Text(stringResource(R.string.onboarding_permissions_skip_title)) },
                text = { Text(stringResource(R.string.onboarding_permissions_skip_description)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSkipDialog = false
                            onFinish()
                        },
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(R.string.onboarding_permissions_skip_anyway))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showSkipDialog = false },
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
    }
}

private enum class OnboardingStep {
    Permissions,
    Theme,
}

// ========== Structural composables matching the reference exactly ==========

@Composable
private fun StepDetails(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun StepTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun StepDescription(description: String) {
    Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun OnboardingHeader() {
    val iconPainter = rememberAppIconPainter()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                text = stringResource(R.string.onboarding_welcome_to),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = iconPainter,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * Loads the app launcher icon as a Compose [Painter].
 *
 * `painterResource(R.mipmap.ic_launcher)` crashes on adaptive icons (XML)
 * because it only supports VectorDrawables and raster assets.
 * This helper uses [Context.getDrawable] which correctly resolves
 * adaptive icons, then converts the resulting Drawable to a [BitmapPainter].
 */
@Composable
@SuppressLint("LocalContextGetResourceValueCall")
private fun rememberAppIconPainter(): Painter {
    val context = LocalContext.current
    return remember {
        val drawable = context.getDrawable(R.mipmap.ic_launcher)!!
        val bitmap = drawableToBitmap(drawable, size = 128)
        BitmapPainter(bitmap.asImageBitmap())
    }
}

private fun drawableToBitmap(
    drawable: Drawable,
    size: Int
): android.graphics.Bitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val bitmap = createBitmap(size, size)
    val canvas = android.graphics.Canvas(bitmap)
    drawable.setBounds(0, 0, size, size)
    drawable.draw(canvas)
    return bitmap
}

// ========== Permission list items matching the reference exactly ==========

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PermissionItem(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    val shapes = ListItemDefaults.segmentedShapes(index = 0, count = 1)
    val colors = ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    )

    SegmentedListItem(
        onClick = if (isGranted) {{ }} else onRequest,
        shapes = shapes,
        colors = colors,
        leadingContent = {
            PermissionIcon(
                icon = icon,
                containerColor = if (isGranted) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                iconColor = if (isGranted) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        },
        trailingContent = {
            Box(modifier = Modifier.padding(start = 4.dp)) {
                if (isGranted) {
                    PermissionIcon(
                        icon = Check,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        iconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        size = 32.dp,
                        iconSize = 16.dp
                    )
                } else {
                    FilledTonalButton(
                        onClick = onRequest,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(
                            text = stringResource(R.string.permission_grant),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        },
        supportingContent = { Text(description) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title)
    }
}

@Composable
private fun PermissionIcon(
    icon: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    iconColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = iconColor
        )
    }
}

// ========== Scrollable column with bottom edge shadow (matching reference) ==========

@Composable
private fun ColumnWithScrollbarEdgeShadow(
    modifier: Modifier = Modifier,
    state: ScrollState = rememberScrollState(),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    edgeShadowHeight: Dp = 40.dp,
    edgeShadowProximity: Dp = 80.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val proximityPx = with(LocalDensity.current) { edgeShadowProximity.toPx() }

    val bottomAlpha by remember(state, proximityPx) {
        derivedStateOf {
            val maxScroll = state.maxValue.takeUnless { it == Int.MAX_VALUE } ?: 0
            if (maxScroll == 0 || proximityPx <= 0f) {
                0f
            } else {
                ((maxScroll - state.value).coerceAtLeast(0) / proximityPx).coerceIn(0f, 1f)
            }
        }
    }

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .matchParentSize()
                .verticalScroll(state),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content
        )

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(edgeShadowHeight)
                .graphicsLayer { alpha = bottomAlpha }
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, surfaceColor)
                    )
                )
        )
    }
}
