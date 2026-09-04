package com.elewashy.nexa.ui.components.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString

/**
 * Shared Material 3 confirmation dialog.
 *
 * Follows the M3 dialog guidance: the dismissive action is placed first and the confirming action
 * last ([AlertDialog] mirrors the order in RTL), both actions are text buttons, and the dialog is
 * dismissible from outside/back. Set [destructive] for irreversible operations so the confirming
 * action is emphasized with the error color; [icon] is an optional supporting visual that helps
 * users recognise the operation at a glance.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfirmationDialog(
    title: String,
    message: AnnotatedString,
    positiveButtonText: String,
    negativeButtonText: String,
    onPositiveClick: () -> Unit,
    onNegativeClick: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
    icon: ImageVector? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = icon?.let { { Icon(it, contentDescription = null) } },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = { onPositiveClick(); onDismiss() },
                shapes = ButtonDefaults.shapes(),
                colors = if (destructive) {
                    ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.textButtonColors()
                },
            ) {
                Text(positiveButtonText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onNegativeClick(); onDismiss() },
                shapes = ButtonDefaults.shapes(),
            ) {
                Text(negativeButtonText)
            }
        },
    )
}
