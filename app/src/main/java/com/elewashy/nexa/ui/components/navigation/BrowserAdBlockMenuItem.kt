package com.elewashy.nexa.ui.components.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.icons.Check
import com.elewashy.nexa.ui.icons.Close
import com.elewashy.nexa.ui.icons.RemoveModerator
import com.elewashy.nexa.ui.icons.Shield
import java.text.NumberFormat

/**
 * Ad blocking for the page in the active tab.
 *
 * @property site the site the switch applies to (the page's site or the parent domain that governs it).
 * @property globalEnabled false when the ad blocker is off everywhere.
 * @property siteEnabled whether the page is filtered.
 * @property blockedOnPage live count for the current page; read only while the menu is shown.
 */
@Immutable
data class BrowserSiteAdBlockState(
    val site: String,
    val globalEnabled: Boolean,
    val siteEnabled: Boolean,
    val blockedOnPage: State<Int>,
)

/**
 * The menu's per-site switch: what is blocked on this page while on, which
 * site is exempt while off. Tapping anywhere on the row toggles it.
 */
@Composable
internal fun SiteAdBlockMenuItem(
    state: BrowserSiteAdBlockState,
    onToggle: (Boolean) -> Unit,
) {
    val checked = state.globalEnabled && state.siteEnabled
    val supporting = when {
        !state.globalEnabled -> stringResource(R.string.adblock_menu_global_off)
        !state.siteEnabled -> stringResource(R.string.adblock_menu_site_off, state.site)
        else -> {
            val count = state.blockedOnPage.value
            val locale = LocalConfiguration.current.locales[0]
            pluralStringResource(R.plurals.adblock_blocked_on_page, count, NumberFormat.getIntegerInstance(locale).format(count))
        }
    }
    DropdownMenuItem(
        text = {
            Column {
                Text(stringResource(R.string.adblock_menu_site))
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        onClick = { onToggle(!checked) },
        enabled = state.globalEnabled,
        leadingIcon = { Icon(if (checked) Shield else RemoveModerator, contentDescription = null) },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = state.globalEnabled,
                thumbContent = {
                    Icon(
                        imageVector = if (checked) Check else Close,
                        contentDescription = null,
                        modifier = Modifier.size(SwitchDefaults.IconSize),
                    )
                },
            )
        },
        contentPadding = PaddingValues(start = 24.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        modifier = Modifier.semantics {
            role = Role.Switch
            toggleableState = ToggleableState(checked)
        },
    )
}
