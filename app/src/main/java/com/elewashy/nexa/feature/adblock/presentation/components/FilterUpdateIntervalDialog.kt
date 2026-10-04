package com.elewashy.nexa.feature.adblock.presentation.components

import android.content.Context
import android.content.res.Resources
import android.icu.text.SimpleDateFormat
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.feature.adblock.domain.model.FilterUpdateInterval
import com.elewashy.nexa.feature.adblock.domain.usecase.FilterUpdateOutcome
import java.util.Date
import java.util.Locale

@Composable
fun FilterUpdateIntervalDialog(
    selected: FilterUpdateInterval,
    onSelect: (FilterUpdateInterval) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.filter_update_interval)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                FilterUpdateInterval.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = option == selected,
                                onClick = { onSelect(option) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            text = stringResource(option.labelRes()),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

fun FilterUpdateInterval.labelRes(): Int = when (this) {
    FilterUpdateInterval.Manual -> R.string.filter_update_interval_manual
    FilterUpdateInterval.SixHours -> R.string.filter_update_interval_6h
    FilterUpdateInterval.TwelveHours -> R.string.filter_update_interval_12h
    FilterUpdateInterval.Daily -> R.string.filter_update_interval_daily
    FilterUpdateInterval.ThreeDays -> R.string.filter_update_interval_3d
    FilterUpdateInterval.Weekly -> R.string.filter_update_interval_weekly
}

/** Snackbar text for a user-requested filter update. */
fun FilterUpdateOutcome.message(resources: Resources): String = when (this) {
    FilterUpdateOutcome.UpToDate -> resources.getString(R.string.adblock_filters_up_to_date)
    is FilterUpdateOutcome.Updated -> resources.getQuantityString(R.plurals.updated_lists_count, lists, lists)
    is FilterUpdateOutcome.PartiallyFailed -> resources.getQuantityString(R.plurals.adblock_failed_lists_title, failed, failed)
    FilterUpdateOutcome.Failed -> resources.getString(R.string.adblock_filters_update_error)
}

/** Localized short date + time ("Oct 5, 18:30" / "5 oct., 18:30"), honouring the 12/24-hour setting. */
fun formatDateTime(context: Context, millis: Long, locale: Locale): String {
    val skeleton = if (DateFormat.is24HourFormat(context)) "MMMdHm" else "MMMdhma"
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}
