// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.ActionDef

/**
 * Editor for a view's [ActionDef] list.  Shows each existing action with
 * inline fields for its parameters, and a "+ Action" dropdown to add new ones.
 */
@Composable
fun ActionEditor(
    actions: List<ActionDef>,
    onUpdate: (List<ActionDef>) -> Unit,
) {
    Text("Actions", style = MaterialTheme.typography.titleMedium)
    Text(
        "Stored for future FORMAT-2 support; the FORMAT-1 device runtime " +
            "does not execute these actions yet.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    actions.forEachIndexed { i, action ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            ActionFields(action) { updated ->
                onUpdate(actions.mapIndexed { j, a -> if (j == i) updated else a })
            }
            TextButton(onClick = {
                onUpdate(actions.filterIndexed { j, _ -> j != i })
            }) { Text("Remove") }
        }
    }

    // "+ Action" dropdown
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("+ Action") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actionDefaults.forEach { (label, factory) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onUpdate(actions + factory())
                        expanded = false
                    },
                )
            }
        }
    }
}

/** The palette of action types a user can add, each with a sensible default. */
private val actionDefaults: List<Pair<String, () -> ActionDef>> = listOf(
    "SetTodo"      to { ActionDef.SetTodo("DONE") },
    "Schedule"     to { ActionDef.Schedule() },
    "SetDeadline"  to { ActionDef.SetDeadline() },
    "SetTags"      to { ActionDef.SetTags() },
    "SetPriority"  to { ActionDef.SetPriority() },
    "Refile"       to { ActionDef.Refile() },
    "Archive"      to { ActionDef.Archive() },
)

/** Renders the inline parameter fields for a single [ActionDef]. */
@Composable
private fun ActionFields(action: ActionDef, onChange: (ActionDef) -> Unit) {
    when (action) {
        is ActionDef.SetTodo -> {
            Text("SetTodo", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.keyword,
                { v -> onChange(action.copy(keyword = v.trim().uppercase())) },
                label = { Text("keyword") }, singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }
        is ActionDef.Schedule -> {
            Text("Schedule", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = action.prompt,
                    onCheckedChange = { onChange(action.copy(prompt = it)) },
                )
                Text("prompt for date")
            }
        }
        is ActionDef.SetDeadline -> {
            Text("SetDeadline", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = action.prompt,
                    onCheckedChange = { onChange(action.copy(prompt = it)) },
                )
                Text("prompt for date")
            }
        }
        is ActionDef.SetTags -> {
            Text("SetTags", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.tags.joinToString(","),
                { v ->
                    onChange(action.copy(tags = v.split(",")
                        .map(String::trim)
                        .filter(String::isNotEmpty)))
                },
                label = { Text("tags (comma-separated)") }, singleLine = true,
                modifier = Modifier.width(220.dp),
            )
        }
        is ActionDef.SetPriority -> {
            Text("SetPriority", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.priority.orEmpty(),
                { v -> onChange(action.copy(priority = v.trim().uppercase().ifBlank { null })) },
                label = { Text("priority (A/B/C)") }, singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }
        is ActionDef.Refile -> {
            Text("Refile", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.target.orEmpty(),
                { v -> onChange(action.copy(target = v.trim().ifBlank { null })) },
                label = { Text("target (optional)") }, singleLine = true,
                modifier = Modifier.width(200.dp),
            )
        }
        is ActionDef.Archive -> {
            Text("Archive", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.style,
                { v -> onChange(action.copy(style = v.trim().ifBlank { "default" })) },
                label = { Text("style") }, singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }
        is ActionDef.Unknown -> {
            Text(
                "Unknown action \"${action.token}\" — kept as written",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
