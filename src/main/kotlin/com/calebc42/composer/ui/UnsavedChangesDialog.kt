// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun UnsavedChangesDialog(
    onSaveAndClose: () -> Unit,
    onDiscardAndClose: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Unsaved Changes") },
        text = { Text("You have unsaved changes. Do you want to save before closing?") },
        confirmButton = {
            TextButton(onClick = onSaveAndClose) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDiscardAndClose) { Text("Discard") }
        }
    )
}
