package com.elewashy.nexa.feature.adblock.presentation.components

import android.icu.text.CompactDecimalFormat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.domain.model.DailyBlockingStats
import com.elewashy.nexa.feature.adblock.domain.model.FilterListCategory
import com.elewashy.nexa.feature.adblock.domain.model.RuleKind
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import java.text.NumberFormat
import java.time.format.TextStyle
import java.util.Locale

/** Locale of the current configuration (follows the per-app language). */
@Composable
fun currentLocale(): Locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT

/** Full, grouped integer formatting ("12,345"). */
@Composable
fun rememberCountFormat(): NumberFormat {
    val locale = currentLocale()
    return remember(locale) { NumberFormat.getIntegerInstance(locale) }
}

/** Short formatting for headline figures ("12K"); exact values go to accessibility labels. */
@Composable
fun rememberCompactCountFormat(): (Long) -> String {
    val locale = currentLocale()
    return remember(locale) {
        val compact = CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
        val full = NumberFormat.getIntegerInstance(locale)
        val format: (Long) -> String = { value -> if (value < COMPACT_THRESHOLD) full.format(value) else compact.format(value) }
        format
    }
}

private const val COMPACT_THRESHOLD = 10_000L

/**
 * Android Settings "main switch" bar: the feature's master toggle on a
 * tinted container, set apart from the regular rows below it.
 */
@Composable
fun AdBlockMainSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val container = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (checked) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 72.dp)
                .padding(horizontal = 24.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = {
                    Icon(
                        imageVector = if (checked) Check else Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        }
    }
}

/** A labelled figure in the statistics summary. */
@Composable
fun StatFigure(
    value: String,
    label: String,
    accessibilityValue: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = "$label: $accessibilityValue" },
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}

/**
 * Blocked requests per day as bars, oldest first in reading order (so it
 * mirrors correctly in RTL). Built from layout, not a canvas, so it scales
 * with font size and needs no manual mirroring; announced as one summary.
 */
@Composable
fun BlockingChart(
    days: List<DailyBlockingStats>,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val countFormat = rememberCountFormat()
    val resources = LocalResources.current
    val max = days.maxOfOrNull { it.blockedRequests }?.coerceAtLeast(1L) ?: 1L
    val summary = remember(days, locale, resources) {
        days.joinToString(", ") { day ->
            resources.getString(
                R.string.adblock_chart_day,
                day.date.dayOfWeek.getDisplayName(TextStyle.FULL, locale),
                countFormat.format(day.blockedRequests),
            )
        }
    }
    val description = stringResource(R.string.adblock_chart_description, summary)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEachIndexed { index, day ->
            val isToday = index == days.lastIndex
            val target = day.blockedRequests.toFloat() / max
            val fraction by animateFloatAsState(targetValue = target, animationSpec = tween(CHART_ANIMATION_MS), label = "bar")
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(CHART_HEIGHT),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .fillMaxHeight(fraction.coerceAtLeast(MIN_BAR_FRACTION))
                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                            .background(
                                if (isToday) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.secondaryContainer,
                            ),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

private val CHART_HEIGHT = 72.dp
private const val MIN_BAR_FRACTION = 0.04f
private const val CHART_ANIMATION_MS = 450

/** Explanatory text under a group of rows, aligned with the section titles. */
@Composable
fun SectionFooter(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 4.dp),
    )
}

/** A rule shown as code: monospace and always left-to-right, even in RTL layouts. */
@Composable
fun RuleText(text: String, modifier: Modifier = Modifier, maxLines: Int = 3) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            textDirection = TextDirection.Ltr,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A host or domain: always left-to-right so dots and labels never reorder in RTL. */
@Composable
fun HostText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Ltr),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Icon + text status line, used for warnings in rows. */
@Composable
fun StatusLine(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    }
}

fun RuleKind.labelRes(): Int = when (this) {
    RuleKind.Block -> R.string.adblock_rule_kind_block
    RuleKind.ImportantBlock -> R.string.adblock_rule_kind_important
    RuleKind.Allow -> R.string.adblock_rule_kind_allow
    RuleKind.ElementHiding -> R.string.adblock_rule_kind_hide
    RuleKind.ElementHidingException -> R.string.adblock_rule_kind_hide_exception
    RuleKind.Scriptlet -> R.string.adblock_rule_kind_scriptlet
    RuleKind.Modifier -> R.string.adblock_rule_kind_modifier
    RuleKind.BadFilter -> R.string.adblock_rule_kind_badfilter
    RuleKind.Comment -> R.string.adblock_rule_kind_comment
    RuleKind.RequiresTrust -> R.string.adblock_rule_kind_requires_trust
    RuleKind.Unsupported -> R.string.adblock_rule_kind_unsupported
}

fun FilterListCategory.labelRes(): Int = when (this) {
    FilterListCategory.Default -> R.string.adblock_category_default
    FilterListCategory.Ads -> R.string.adblock_category_ads
    FilterListCategory.Privacy -> R.string.adblock_category_privacy
    FilterListCategory.Malware -> R.string.adblock_category_malware
    FilterListCategory.Annoyances -> R.string.adblock_category_annoyances
    FilterListCategory.Multipurpose -> R.string.adblock_category_multipurpose
    FilterListCategory.Regional -> R.string.adblock_category_regional
    FilterListCategory.Imported -> R.string.adblock_category_imported
}

/** Localized short size ("1.2 MB"), following the app locale of [context]. */
fun formatBytes(context: android.content.Context, bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(context, bytes.coerceAtLeast(0L))

/** Localized medium date ("Oct 5, 2026"). */
fun formatLocalDate(date: java.time.LocalDate, locale: Locale): String =
    java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
        .withLocale(locale)
        .format(date)
