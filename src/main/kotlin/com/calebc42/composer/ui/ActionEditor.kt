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
import com.calebc42.composer.model.PackManifest

/**
 * Editor for a view's [ActionDef] list.  Shows each existing action with
 * inline fields for its parameters, and a "+ Action" dropdown to add new
 * ones — including, when a pack manifest is selected [pack], that pack's
 * annotated actions with their typed args.
 */
@Composable
fun ActionEditor(
    actions: List<ActionDef>,
    onUpdate: (List<ActionDef>, coalesceKey: String?) -> Unit,
    pack: PackManifest? = null,
) {
    Text("Actions", style = MaterialTheme.typography.titleMedium)
    Text(
        "Per-view org actions shown on record cards and applied by the device runtime.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))

    actions.forEachIndexed { i, action ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            ActionFields(action, pack) { updated, key ->
                onUpdate(
                    actions.mapIndexed { j, a -> if (j == i) updated else a },
                    key?.let { "$i.$it" },
                )
            }
            TextButton(onClick = {
                onUpdate(actions.filterIndexed { j, _ -> j != i }, null)
            }) { Text("Remove") }
        }
    }

    // "+ Action" dropdown: the built-in org vocabulary, then the selected
    // pack's annotated actions.
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("+ Action") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actionDefaults.forEach { (label, factory) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onUpdate(actions + factory(), null)
                        expanded = false
                    },
                )
            }
            pack?.actions?.forEach { packAction ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text("${pack.pack_id}/${packAction.action}")
                            packAction.doc?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    },
                    onClick = {
                        onUpdate(
                            actions + ActionDef.PackAction(pack.pack_id, packAction.action),
                            null,
                        )
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
private fun ActionFields(
    action: ActionDef,
    pack: PackManifest?,
    onChange: (ActionDef, coalesceKey: String?) -> Unit,
) {
    when (action) {
        is ActionDef.SetTodo -> {
            Text("SetTodo", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.keyword,
                { v -> onChange(action.copy(keyword = v.trim().uppercase()), "todo") },
                label = { Text("keyword") }, singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }
        is ActionDef.Schedule -> {
            Text("Schedule", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = action.prompt,
                    onCheckedChange = { onChange(action.copy(prompt = it), null) },
                )
                Text("prompt for date")
            }
        }
        is ActionDef.SetDeadline -> {
            Text("SetDeadline", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = action.prompt,
                    onCheckedChange = { onChange(action.copy(prompt = it), null) },
                )
                Text("prompt for date")
            }
        }
        is ActionDef.SetTags -> {
            Text("SetTags", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.tags.joinToString(","),
                { v ->
                    onChange(
                        action.copy(tags = v.split(",")
                            .map(String::trim)
                            .filter(String::isNotEmpty)),
                        "tags",
                    )
                },
                label = { Text("tags (comma-separated)") }, singleLine = true,
                modifier = Modifier.width(220.dp),
            )
        }
        is ActionDef.SetPriority -> {
            Text("SetPriority", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.priority.orEmpty(),
                { v -> onChange(
                    action.copy(priority = v.trim().uppercase().ifBlank { null }),
                    "priority",
                ) },
                label = { Text("priority (A/B/C)") }, singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }
        is ActionDef.Refile -> {
            Text("Refile", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.target.orEmpty(),
                { v -> onChange(
                    action.copy(target = v.trim().ifBlank { null }),
                    "refile",
                ) },
                label = { Text("target (optional)") }, singleLine = true,
                modifier = Modifier.width(200.dp),
            )
        }
        is ActionDef.Archive -> {
            Text("Archive", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                action.style,
                { v -> onChange(
                    action.copy(style = v.trim().ifBlank { "default" }),
                    "archive",
                ) },
                label = { Text("style") }, singleLine = true,
                modifier = Modifier.width(140.dp),
            )
        }
        is ActionDef.PackAction -> {
            val declared = pack?.takeIf { it.pack_id == action.packId }
                ?.action(action.action)
            Column {
                Text("Pack: ${action.packId}/${action.action}",
                     style = MaterialTheme.typography.bodyMedium)
                declared?.doc?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                declared?.args?.takeIf { it.isNotEmpty() }?.let { args ->
                    Text(
                        "Args: " + args.joinToString(", ") { a ->
                            a.name + ": " + a.type + (if (a.required) " (required)" else "")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                action.args.orEmpty(),
                { v ->
                    // Args land verbatim inside the token's `(...)`, whose
                    // grammar is `[^)]*` on both parsers — a ')' or newline
                    // would make the saved document unparseable (and it can't
                    // be re-opened). Strip exactly those; keep interior
                    // spaces (a multi-word value is legal) and don't trim on
                    // keystroke (that ate every trailing space as typed).
                    val cleaned = v.filterNot { it == ')' || it == '\n' || it == '\r' }
                    onChange(action.copy(args = cleaned.ifBlank { null }), "packargs")
                },
                label = { Text("args (optional)") }, singleLine = true,
                modifier = Modifier.width(220.dp),
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
