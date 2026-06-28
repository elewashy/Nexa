package com.elewashy.nexa.ui.components.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConfirmationDialog(
    title: String,
    message: AnnotatedString,
    positiveButtonText: String,
    negativeButtonText: String,
    onPositiveClick: () -> Unit,
    onNegativeClick: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = { onPositiveClick(); onDismiss() },
                shapes = ButtonDefaults.shapes()
            ) {
                Text(positiveButtonText)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { onNegativeClick(); onDismiss() },
                shapes = ButtonDefaults.shapes()
            ) {
                Text(negativeButtonText)
            }
        }
    )
}
