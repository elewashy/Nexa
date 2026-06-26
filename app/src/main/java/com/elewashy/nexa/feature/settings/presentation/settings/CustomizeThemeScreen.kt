package com.elewashy.nexa.feature.settings.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.theme.AppTheme
import com.elewashy.nexa.ui.theme.DefaultThemeColor
import com.elewashy.nexa.ui.theme.NexaTheme
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme
import com.elewashy.nexa.ui.icons.ArrowBackFilled
import com.elewashy.nexa.ui.icons.AutoMode
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.DarkMode
import com.elewashy.nexa.ui.icons.LightMode
import com.elewashy.nexa.ui.icons.Palette

private data class ThemePalette(
    val nameRes: Int,
    val seedColor: Color,
)

private val PaletteColors = listOf(
    ThemePalette(R.string.palette_dynamic, Color.Transparent),
    ThemePalette(R.string.palette_crimson, Color(0xFFEC5464)),
    ThemePalette(R.string.palette_rose, Color(0xFFD81B60)),
    ThemePalette(R.string.palette_purple, Color(0xFF8E24AA)),
    ThemePalette(R.string.palette_deep_purple, Color(0xFF5E35B1)),
    ThemePalette(R.string.palette_indigo, Color(0xFF3949AB)),
    ThemePalette(R.string.palette_blue, Color(0xFF1E88E5)),
    ThemePalette(R.string.palette_sky_blue, Color(0xFF039BE5)),
    ThemePalette(R.string.palette_cyan, Color(0xFF00ACC1)),
    ThemePalette(R.string.palette_teal, Color(0xFF00897B)),
    ThemePalette(R.string.palette_green, Color(0xFF43A047)),
    ThemePalette(R.string.palette_light_green, Color(0xFF7CB342)),
    ThemePalette(R.string.palette_lime, Color(0xFFC0CA33)),
    ThemePalette(R.string.palette_yellow, Color(0xFFFDD835)),
    ThemePalette(R.string.palette_amber, Color(0xFFFFB300)),
    ThemePalette(R.string.palette_orange, Color(0xFFFB8C00)),
    ThemePalette(R.string.palette_deep_orange, Color(0xFFF4511E)),
    ThemePalette(R.string.palette_brown, Color(0xFF6D4C41)),
    ThemePalette(R.string.palette_grey, Color(0xFF757575)),
    ThemePalette(R.string.palette_blue_grey, Color(0xFF546E7A)),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CustomizeThemeScreen(
    onBackClick: () -> Unit,
    viewModel: SettingsViewModel,
    bottomBar: @Composable () -> Unit = {},
) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()
    val selectedThemeColorInt by viewModel.selectedThemeColor.collectAsStateWithLifecycle()
    val selectedThemeColor = Color(selectedThemeColorInt)
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    val useSplitLayout = adaptiveInfo.useTwoPane || (adaptiveInfo.isMedium && adaptiveInfo.isLandscape)
    val scrollState = rememberScrollState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(
        canScroll = { scrollState.canScrollBackward || scrollState.canScrollForward }
    )

    Scaffold(
        topBar = {
            MediumFlexibleTopAppBar(
                title = { Text(stringResource(R.string.customize_theme)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = ArrowBackFilled,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = bottomBar,
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    ) { paddingValues ->
        if (useSplitLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                Box(
                        modifier = Modifier
                            .weight(0.4f)
                            .fillMaxHeight()
                            .padding(adaptiveInfo.horizontalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    ThemeMockup(
                        theme = theme,
                        pureBlack = pureBlack,
                        themeColor = selectedThemeColor,
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .heightIn(max = if (adaptiveInfo.isTvLike) 420.dp else 320.dp),
                    )
                }
                ThemeControls(
                    theme = theme,
                    pureBlack = pureBlack,
                    selectedThemeColor = selectedThemeColor,
                    onThemeSelected = viewModel::setTheme,
                    onPureBlackSelected = {
                        viewModel.setTheme(AppTheme.DARK)
                        viewModel.setPureBlack(true)
                    },
                    onPureBlackChange = viewModel::setPureBlack,
                    onColorSelected = { viewModel.setSelectedThemeColor(it.toArgb()) },
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(end = adaptiveInfo.horizontalPadding, top = 16.dp, bottom = 32.dp),
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(28.dp))
                ThemeMockup(
                    theme = theme,
                    pureBlack = pureBlack,
                    themeColor = selectedThemeColor,
                    modifier = Modifier
                        .width(if (adaptiveInfo.widthDp < 360) 104.dp else 120.dp)
                        .height(if (adaptiveInfo.widthDp < 360) 208.dp else 240.dp),
                )
                Spacer(modifier = Modifier.height(28.dp))
                ThemeControls(
                    theme = theme,
                    pureBlack = pureBlack,
                    selectedThemeColor = selectedThemeColor,
                    onThemeSelected = viewModel::setTheme,
                    onPureBlackSelected = {
                        viewModel.setTheme(AppTheme.DARK)
                        viewModel.setPureBlack(true)
                    },
                    onPureBlackChange = viewModel::setPureBlack,
                    onColorSelected = { viewModel.setSelectedThemeColor(it.toArgb()) },
                    modifier = Modifier.padding(bottom = 32.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeControls(
    theme: AppTheme,
    pureBlack: Boolean,
    selectedThemeColor: Color,
    onThemeSelected: (AppTheme) -> Unit,
    onPureBlackSelected: () -> Unit,
    onPureBlackChange: (Boolean) -> Unit,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val adaptiveInfo = rememberAdaptiveLayoutInfo()
    Card(
        modifier = modifier
            .widthIn(max = adaptiveInfo.listMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.theme_mode),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModeCircle(
                        icon = AutoMode,
                        contentDescription = stringResource(R.string.cd_system_mode),
                        selected = theme == AppTheme.SYSTEM,
                        dark = isSystemInDarkTheme(),
                        pureBlack = false,
                        onClick = { onThemeSelected(AppTheme.SYSTEM) },
                    )
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(32.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                    ModeCircle(
                        icon = LightMode,
                        contentDescription = stringResource(R.string.cd_light_mode),
                        selected = theme == AppTheme.LIGHT,
                        dark = false,
                        pureBlack = false,
                        onClick = {
                            onThemeSelected(AppTheme.LIGHT)
                            onPureBlackChange(false)
                        },
                    )
                    ModeCircle(
                        icon = DarkMode,
                        contentDescription = stringResource(R.string.cd_dark_mode),
                        selected = theme == AppTheme.DARK && !pureBlack,
                        dark = true,
                        pureBlack = false,
                        onClick = {
                            onThemeSelected(AppTheme.DARK)
                            onPureBlackChange(false)
                        },
                    )
                    ModeCircle(
                        icon = null,
                        contentDescription = stringResource(R.string.cd_pure_black_mode),
                        selected = theme == AppTheme.DARK && pureBlack,
                        dark = true,
                        pureBlack = true,
                        onClick = onPureBlackSelected,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.color_palette),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                ) {
                    items(PaletteColors) { palette ->
                        val colorToSave = if (palette.seedColor == Color.Transparent) DefaultThemeColor else palette.seedColor
                        PaletteItem(
                            palette = palette,
                            selected = selectedThemeColor == colorToSave,
                            onClick = { onColorSelected(colorToSave) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCircle(
    icon: ImageVector?,
    contentDescription: String,
    selected: Boolean,
    dark: Boolean,
    pureBlack: Boolean,
    onClick: () -> Unit,
) {
    val modeScheme = rememberDynamicColorScheme(
        seedColor = DefaultThemeColor,
        isDark = dark,
        style = PaletteStyle.TonalSpot,
    )
    val fillColor = if (pureBlack) Color.Black else modeScheme.surface
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "modeBorderWidth",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "modeScale",
    )
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fillColor)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, MaterialTheme.colorScheme.inversePrimary, CircleShape)
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = modeScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        } else if (selected) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + scaleIn(initialScale = 0.3f),
            ) {
                Icon(
                    imageVector = Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.inversePrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun PaletteItem(
    palette: ThemePalette,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val isDynamic = palette.seedColor == Color.Transparent
    val previewSeed = if (isDynamic) DefaultThemeColor else palette.seedColor
    val colorScheme = rememberDynamicColorScheme(
        seedColor = previewSeed,
        isDark = isSystemInDarkTheme(),
        style = PaletteStyle.TonalSpot,
    )
    val cornerRadius by animateDpAsState(
        targetValue = if (selected) 12.dp else 24.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium),
        label = "paletteCornerRadius",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (selected) 3.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "paletteBorderWidth",
    )
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "paletteScale",
    )
    val shape = RoundedCornerShape(cornerRadius)
    val interactionSource = remember { MutableInteractionSource() }
    val paletteName = stringResource(palette.nameRes)

    Box(
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, MaterialTheme.colorScheme.inversePrimary, shape)
                } else {
                    Modifier
                }
            )
            .clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
            .semantics { contentDescription = paletteName },
        contentAlignment = Alignment.Center,
    ) {
        if (isDynamic) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Palette,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(colorScheme.onPrimary, Offset.Zero, Size(size.width, size.height / 2))
                drawRect(colorScheme.secondary, Offset(0f, size.height / 2), Size(size.width / 2, size.height / 2))
                drawRect(colorScheme.tertiary, Offset(size.width / 2, size.height / 2), Size(size.width / 2, size.height / 2))
            }
        }
    }
}

@Composable
private fun ThemeMockup(
    theme: AppTheme,
    pureBlack: Boolean,
    themeColor: Color,
    modifier: Modifier = Modifier,
) {
    val useDark = when (theme) {
        AppTheme.SYSTEM -> isSystemInDarkTheme()
        AppTheme.LIGHT -> false
        AppTheme.DARK -> true
    }
    NexaTheme(
        darkTheme = useDark,
        dynamicColor = false,
        pureBlack = pureBlack && useDark,
        selectedThemeColor = themeColor,
    ) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (index == 2) 0.65f else 1f)
                            .height(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(3) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        }
    }
}
