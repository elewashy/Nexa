package com.elewashy.nexa.ui.components.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.ui.adaptive.rememberAdaptiveLayoutInfo
import com.elewashy.nexa.ui.components.common.AppTopBar
import kotlin.math.absoluteValue

/**
 * Shared adaptive selector for settings represented by a phone preview.
 * HorizontalPager supplies platform scrolling, nested gesture arbitration, RTL behavior and
 * accessibility semantics instead of maintaining a custom carousel.
 */
@Composable
fun <T> PhoneDesignSelectorScreen(
    title: String,
    description: String,
    options: List<T>,
    selectedOption: T,
    optionTitle: @Composable (T) -> String,
    optionDescription: @Composable (T) -> String,
    applyLabel: String,
    appliedLabel: String,
    onOptionSelected: (T) -> Unit,
    onBackClick: () -> Unit,
    preview: @Composable (option: T, modifier: Modifier) -> Unit,
    bottomBar: @Composable (() -> Unit)? = null,
) {
    require(options.isNotEmpty())
    BackHandler(onBack = onBackClick)
    val adaptive = rememberAdaptiveLayoutInfo()
    val pagerState = rememberPagerState(
        initialPage = options.indexOf(selectedOption).coerceAtLeast(0),
        pageCount = options::size,
    )
    LaunchedEffect(options, selectedOption) {
        val selectedPage = options.indexOf(selectedOption).takeIf { it >= 0 } ?: 0
        if (pagerState.currentPage != selectedPage) pagerState.scrollToPage(selectedPage)
    }
    val shownOption = options[pagerState.currentPage.coerceIn(options.indices)]
    val isApplied = shownOption == selectedOption

    Scaffold(
        topBar = { AppTopBar(title = title, onBackClick = onBackClick) },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = adaptive.horizontalPadding, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = { onOptionSelected(shownOption) },
                    enabled = !isApplied,
                    modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().height(52.dp),
                ) {
                    AnimatedContent(
                        targetState = isApplied,
                        transitionSpec = { fadeIn(tween(150)) togetherWith fadeOut(tween(90)) },
                        label = "phoneDesignApplyState",
                    ) { applied -> Text(if (applied) appliedLabel else applyLabel) }
                }
                bottomBar?.invoke()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = adaptive.horizontalPadding).widthIn(max = 560.dp),
            )
            Spacer(Modifier.height(12.dp))
            HorizontalPager(
                state = pagerState,
                pageSpacing = 24.dp,
                beyondViewportPageCount = 1,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) { page ->
                val option = options[page]
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val shortHeight = maxHeight < 380.dp
                    val reservedTextHeight = if (shortHeight) 64.dp else 112.dp
                    val maxPhoneWidth = if (adaptive.isCompact) 196.dp else 224.dp
                    val phoneWidth = ((maxHeight - reservedTextHeight) / 2).coerceIn(84.dp, maxPhoneWidth)
                    Column(
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            val pageOffset = (
                                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                            ).absoluteValue.coerceIn(0f, 1f)
                            val scale = 1f - pageOffset * 0.06f
                            scaleX = scale
                            scaleY = scale
                            alpha = 1f - pageOffset * 0.28f
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        preview(option, Modifier.width(phoneWidth).aspectRatio(0.5f))
                        Spacer(Modifier.height(if (shortHeight) 8.dp else 16.dp))
                        Text(
                            text = optionTitle(option),
                            style = if (shortHeight) MaterialTheme.typography.titleMedium
                            else MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (!shortHeight) {
                            Text(
                                text = optionDescription(option),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 32.dp).widthIn(max = 480.dp),
                            )
                        }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(vertical = 8.dp),
            ) {
                options.indices.forEach { index ->
                    Box(
                        Modifier
                            .size(if (index == pagerState.currentPage) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant
                            )
                    )
                }
            }
        }
    }
}

/** Shared phone shell so design selectors use identical geometry and elevation. */
@Composable
fun PhonePreviewFrame(
    modifier: Modifier = Modifier,
    content: @Composable BoxWithConstraintsScope.() -> Unit,
) {
    Surface(
        modifier = modifier.border(2.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(32.dp)),
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
    ) {
        BoxWithConstraints(content = content)
    }
}
