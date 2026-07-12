// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun ExpressionDialog(
    initialValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Expression Editor") },
        text = {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(
                    "Write an org-ql query to filter records. For example: `todo:NEXT tags:work` or `(and (todo \"NEXT\") (tags \"work\"))`.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("org-ql filter query") },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(query) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
