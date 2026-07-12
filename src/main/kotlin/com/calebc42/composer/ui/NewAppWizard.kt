// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.project.Templates
import com.calebc42.composer.project.UseCase

/** The data-first New App flow: pick a use case or define columns manually. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewAppWizard(onCreate: (AppSpec) -> Unit, onDismiss: () -> Unit) {
    var id by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("") }
    var viewTitle by remember { mutableStateOf("Items") }
    var kind by remember { mutableStateOf(ViewKind.TABLE) }
    val columns = remember {
        mutableStateListOf<Pair<String, ColType>>("Name" to ColType.Text)
    }
    var external by remember { mutableStateOf(false) }
    var backendPath by remember { mutableStateOf("/sdcard/org/data.org") }

    /** Apply a use-case preset to all wizard fields. */
    fun applyUseCase(uc: UseCase) {
        id = uc.id
        label = uc.label
        icon = ""
        viewTitle = uc.label
        kind = uc.kind
        columns.clear()
        if (uc.columns.isNotEmpty()) {
            columns.addAll(uc.columns)
        } else {
            // For records-type kinds, show schema props as placeholder columns
            // so the user sees something meaningful in the column editor.
            uc.schema.forEach { sf ->
                columns.add((sf.label ?: sf.prop) to ColType.Text)
            }
        }
        when (val src = uc.source) {
            is SourceRef.File -> {
                external = true
                backendPath = src.file
            }
            is SourceRef.Dir -> {
                external = true
                backendPath = src.dir
            }
            null -> {
                external = false
                backendPath = "/sdcard/org/data.org"
            }
        }
    }

    /** Whether the current kind is a records-type (schema-driven). */
    val isRecordsKind = kind in listOf(
        ViewKind.RECORDS, ViewKind.NOTES, ViewKind.BOARD,
        ViewKind.CALENDAR, ViewKind.GALLERY, ViewKind.TREE,
    )

    /** Find the matching UseCase if id matches one. */
    val matchedUseCase = UseCase.entries.firstOrNull { it.id == id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New app") },
        confirmButton = {
            Button(
                enabled = AppSpec.ID_RE.matches(id) &&
                    columns.isNotEmpty() && columns.none { it.first.isBlank() },
                onClick = {
                    // If the user picked a use case and hasn't changed the id,
                    // use buildFromUseCase for a fully-specified result.
                    val matched = matchedUseCase
                    if (matched != null && matched.label == label) {
                        onCreate(Templates.buildFromUseCase(matched))
                    } else {
                        onCreate(Templates.build(
                            id = id,
                            label = label.ifBlank { id.replaceFirstChar { it.uppercase() } },
                            icon = icon.ifBlank { null },
                            viewTitle = viewTitle.ifBlank { "Items" },
                            kind = kind,
                            columns = if (!isRecordsKind) columns.toList() else emptyList(),
                            schema = if (isRecordsKind && matched != null) matched.schema else emptyList(),
                            backendPath = if (external) backendPath else null,
                        ))
                    }
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {

                // ── Use-case quick-start ──────────────────────────
                Text("Start from a use case",
                     style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    UseCase.entries.forEach { uc ->
                        OutlinedButton(onClick = { applyUseCase(uc) }) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(uc.label, style = MaterialTheme.typography.labelLarge)
                                Text(uc.description,
                                     style = MaterialTheme.typography.bodySmall,
                                     color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                // ── Identity fields ──────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(id, { id = it.lowercase() },
                                      label = { Text("id (slug)") },
                                      singleLine = true, modifier = Modifier.width(160.dp))
                    OutlinedTextField(label, { label = it },
                                      label = { Text("label") },
                                      singleLine = true, modifier = Modifier.width(160.dp))
                    OutlinedTextField(icon, { icon = it },
                                      label = { Text("icon") },
                                      singleLine = true, modifier = Modifier.width(140.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(viewTitle, { viewTitle = it },
                                      label = { Text("view title") },
                                      singleLine = true, modifier = Modifier.width(200.dp))
                }

                // ── View kind selector ───────────────────────────
                Spacer(Modifier.height(12.dp))
                Text("View kind", style = MaterialTheme.typography.titleSmall)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ViewKind.entries.forEach { vk ->
                        val selected = vk == kind
                        if (selected) {
                            Button(onClick = {}) {
                                Text(vk.name)
                            }
                        } else {
                            OutlinedButton(onClick = { kind = vk }) {
                                Text(vk.name)
                            }
                        }
                    }
                }

                // ── Columns (TABLE / CHECKLIST) ──────────────────
                Spacer(Modifier.height(12.dp))
                Text(if (isRecordsKind) "Fields (preview)" else "Columns",
                     style = MaterialTheme.typography.titleSmall)
                if (isRecordsKind) {
                    Text("Records-type views use a schema. " +
                         "Column names below are for reference only.",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                columns.forEachIndexed { i, (name, type) ->
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 2.dp)) {
                        OutlinedTextField(
                            name, { v -> columns[i] = v to columns[i].second },
                            singleLine = true, modifier = Modifier.width(180.dp),
                        )
                        if (!isRecordsKind) {
                            ColTypePicker(type, onPick = { t -> columns[i] = columns[i].first to t })
                        }
                        TextButton(onClick = { columns.removeAt(i) },
                                   enabled = columns.size > 1) { Text("×") }
                    }
                }
                OutlinedButton(onClick = { columns += ("New column" to ColType.Text) }) {
                    Text(if (isRecordsKind) "+ Field" else "+ Column")
                }

                // ── Data source ──────────────────────────────────
                Spacer(Modifier.height(12.dp))
                Text("Where does the data live?",
                     style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!external, onClick = { external = false })
                    Text("Inside the app document (simplest)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(external, onClick = { external = true })
                    Text("In an org file on the device")
                }
                if (external) {
                    OutlinedTextField(
                        backendPath, { backendPath = it },
                        label = { Text("device path") }, singleLine = true,
                        modifier = Modifier.width(320.dp),
                    )
                    Text("Scaffolded automatically (heading + header row) if missing.",
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
