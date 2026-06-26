package com.elewashy.nexa.feature.update.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.elewashy.nexa.R
import com.elewashy.nexa.ui.icons.Update

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AvailableUpdateDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    setShowUpdateDialogOnLaunch: (Boolean) -> Unit,
    newVersion: String
) {
    var dontShowAgain by rememberSaveable { mutableStateOf(false) }
    val dismissDialog = {
        setShowUpdateDialogOnLaunch(!dontShowAgain)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = dismissDialog,
        confirmButton = {
            TextButton(
                onClick = {
                    dismissDialog()
                    onConfirm()
                },
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.show))
            }
        },
        dismissButton = {
            TextButton(
                onClick = dismissDialog,
                shapes = ButtonDefaults.shapes()
            ) {
                Text(stringResource(R.string.dismiss))
            }
        },
        icon = {
            Icon(imageVector = Update, contentDescription = null)
        },
        title = {
            Text(stringResource(R.string.update_available))
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    text = stringResource(R.string.update_available_dialog_description, newVersion)
                )
                ListItem(
                    modifier = Modifier.clickable { dontShowAgain = !dontShowAgain },
                    headlineContent = {
                        Text(stringResource(R.string.never_show_again))
                    },
                    leadingContent = {
                        Checkbox(
                            checked = dontShowAgain,
                            onCheckedChange = { dontShowAgain = it }
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    )
}
