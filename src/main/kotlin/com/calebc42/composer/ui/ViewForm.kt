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
import androidx.compose.material3.RadioButton
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
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ChecklistItem
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec

// ─── App form ────────────────────────────────────────────────────────────────

@Composable
fun AppForm(session: EditorSession) {
    val spec = session.spec
    Text("App", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            spec.id, { v -> session.update { it.copy(id = v.lowercase()) } },
            label = { Text("id (slug)") }, singleLine = true,
            isError = !com.calebc42.composer.model.AppSpec.ID_RE.matches(spec.id),
            modifier = Modifier.width(200.dp),
        )
        OutlinedTextField(
            spec.label.orEmpty(),
            { v -> session.update { it.copy(label = v.ifBlank { null }) } },
            label = { Text("launcher label") }, singleLine = true,
            modifier = Modifier.width(220.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            spec.icon.orEmpty(),
            { v -> session.update { it.copy(icon = v.ifBlank { null }) } },
            label = { Text("icon (Material name)") }, singleLine = true,
            modifier = Modifier.width(200.dp),
        )
        OutlinedTextField(
            spec.order?.toString().orEmpty(),
            { v -> session.update { it.copy(order = v.toIntOrNull()) } },
            label = { Text("launcher order") }, singleLine = true,
            modifier = Modifier.width(140.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Icons are Material symbol names (snake_case), e.g. kitchen, " +
            "table_chart, checklist. Unknown names render a placeholder.",
        style = MaterialTheme.typography.bodySmall,
    )
}

// ─── View form ───────────────────────────────────────────────────────────────

@Composable
fun ViewForm(session: EditorSession, index: Int) {
    val view = session.spec.views[index]
    fun edit(transform: (ViewSpec) -> ViewSpec) =
        session.update { ModelOps.updateView(it, index, transform) }

    Text("View — ${view.title}", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            view.title, { v -> edit { it.copy(title = v) } },
            label = { Text("title (tab label)") }, singleLine = true,
            modifier = Modifier.width(220.dp),
        )
        OutlinedTextField(
            view.icon.orEmpty(),
            { v -> edit { it.copy(icon = v.ifBlank { null }) } },
            label = { Text("tab icon") }, singleLine = true,
            modifier = Modifier.width(180.dp),
        )
        OutlinedTextField(
            view.order?.toString().orEmpty(),
            { v -> edit { it.copy(order = v.toIntOrNull()) } },
            label = { Text("tab order") }, singleLine = true,
            modifier = Modifier.width(120.dp),
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Kind:", style = MaterialTheme.typography.bodyMedium)
        RadioButton(view.kind == ViewKind.TABLE,
                    onClick = { edit { it.copy(kind = ViewKind.TABLE) } })
        Text("table")
        RadioButton(view.kind == ViewKind.CHECKLIST,
                    onClick = { edit { it.copy(kind = ViewKind.CHECKLIST) } })
        Text("checklist")
        RadioButton(view.kind == ViewKind.RECORDS,
                    onClick = {
                        edit {
                            it.copy(kind = ViewKind.RECORDS,
                                    schema = it.schema.ifEmpty {
                                        listOf(SchemaField("ITEM", "Name"))
                                    })
                        }
                    })
        Text("records (headings + properties)")
    }

    Spacer(Modifier.height(8.dp))
    SourceEditor(view, ::edit)

    Spacer(Modifier.height(16.dp))
    when {
        view.kind == ViewKind.CHECKLIST -> ChecklistEditor(view, ::edit)
        view.kind == ViewKind.RECORDS -> RecordsSchemaEditor(view, ::edit)
        view.source == null -> InlineTableEditor(view, ::edit)
        else -> ExternalColumnsEditor(view, ::edit)
    }
}

// ─── Records: schema + filter (the data lives on the device) ────────────────

@Composable
private fun RecordsSchemaEditor(view: ViewSpec, edit: ((ViewSpec) -> ViewSpec) -> Unit) {
    Text("Schema — fields of each record",
         style = MaterialTheme.typography.titleMedium)
    Text("A record is a heading with a property drawer. Special names — " +
             "ITEM (title), TODO, DEADLINE, SCHEDULED, PRIORITY — run org's " +
             "own machinery; anything else is a drawer property. A PROP_ALL " +
             "declaration in the data file supplies enum choices and wins " +
             "over the type here.",
         style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    view.schema.forEachIndexed { i, field ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp)) {
            OutlinedTextField(
                field.prop,
                { v ->
                    edit {
                        it.copy(schema = it.schema.mapIndexed { j, f ->
                            if (j == i) SchemaField.of(v.trim(), f.label) else f
                        })
                    }
                },
                label = { Text("property") }, singleLine = true,
                modifier = Modifier.width(170.dp),
            )
            OutlinedTextField(
                field.label.orEmpty(),
                { v ->
                    edit {
                        it.copy(schema = it.schema.mapIndexed { j, f ->
                            if (j == i) f.copy(label = v.ifBlank { null }) else f
                        })
                    }
                },
                label = { Text("label") }, singleLine = true,
                modifier = Modifier.width(150.dp),
            )
            ColTypePicker(
                view.colTypes.getOrElse(i) { ColType.Text },
                onPick = { t ->
                    edit {
                        val width = it.schema.size
                        it.copy(colTypes = (0 until width).map { c ->
                            if (c == i) t else it.colTypes.getOrElse(c) { ColType.Text }
                        })
                    }
                },
            )
            TextButton(onClick = {
                edit {
                    it.copy(schema = it.schema.filterIndexed { j, _ -> j != i },
                            colTypes = it.colTypes.filterIndexed { j, _ -> j != i })
                }
            }, enabled = view.schema.size > 1) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = {
        edit { it.copy(schema = it.schema + SchemaField("NewProp"),
                       colTypes = it.colTypes + ColType.Text) }
    }) { Text("+ Field") }

    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        view.filter.orEmpty(),
        { v -> edit { it.copy(filter = v.trim().ifBlank { null }) } },
        label = { Text("filter (org match syntax, e.g. +active+Tier=\"Gold\")") },
        singleLine = true, modifier = Modifier.width(420.dp),
    )
}

@Composable
private fun SourceEditor(view: ViewSpec, edit: ((ViewSpec) -> ViewSpec) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Data lives:", style = MaterialTheme.typography.bodyMedium)
        RadioButton(view.source == null,
                    onClick = { edit { it.copy(source = null) } })
        Text("inline (in the app document)")
        RadioButton(view.source != null,
                    onClick = {
                        edit {
                            it.copy(source = it.source
                                ?: SourceRef("/sdcard/org/data.org", it.title))
                        }
                    })
        Text("external org file")
    }
    view.source?.let { source ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                source.file,
                { v -> edit { it.copy(source = source.copy(file = v)) } },
                label = { Text("device path") }, singleLine = true,
                modifier = Modifier.width(320.dp),
            )
            OutlinedTextField(
                source.heading.orEmpty(),
                { v -> edit { it.copy(source = source.copy(heading = v.ifBlank { null })) } },
                label = { Text("heading (optional)") }, singleLine = true,
                modifier = Modifier.width(220.dp),
            )
        }
        if (view.kind == ViewKind.RECORDS) {
            Text(
                "⚠ Bring-your-own file: the runtime edits it with org-mode's " +
                    "own commands. Property drawers in managed records are " +
                    "created/normalized, and deleting a record deletes its " +
                    "whole subtree. Keep the file under git or backups. " +
                    "(docs/FORMAT.md → \"What the runtime does to your files\")",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// ─── Inline table: schema + data in one grid ────────────────────────────────

@Composable
private fun InlineTableEditor(view: ViewSpec, edit: ((ViewSpec) -> ViewSpec) -> Unit) {
    val table = ModelOps.firstTable(view)
        ?: BodyElement.Table(emptyList(), emptyList())

    Text("Datasource table", style = MaterialTheme.typography.titleMedium)
    Text("The header row is the schema; every row is a record.",
         style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))

    // Schema: one editor block per column.
    table.header.forEachIndexed { col, name ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp)) {
            OutlinedTextField(
                name, { v -> edit { ModelOps.setColumnName(it, col, v) } },
                label = { Text("column ${col + 1}") }, singleLine = true,
                modifier = Modifier.width(200.dp),
            )
            ColTypePicker(
                view.colTypes.getOrElse(col) { ColType.Text },
                onPick = { t -> edit { ModelOps.setColumnType(it, col, t) } },
            )
            TextButton(onClick = { edit { ModelOps.removeColumn(it, col) } }) {
                Text("Remove")
            }
        }
    }
    OutlinedButton(onClick = { edit { ModelOps.addColumn(it, "New column") } }) {
        Text("+ Column")
    }

    Spacer(Modifier.height(16.dp))
    Text("Rows", style = MaterialTheme.typography.titleMedium)
    table.rows.forEachIndexed { r, row ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 2.dp)) {
            row.forEachIndexed { c, cell ->
                if (view.colTypes.getOrElse(c) { ColType.Text } == ColType.Checkbox) {
                    Checkbox(
                        checked = cell.matches(Regex("""\[[xX]\]""")),
                        onCheckedChange = { on ->
                            edit { ModelOps.setCell(it, r, c, if (on) "[X]" else "[ ]") }
                        },
                    )
                } else {
                    OutlinedTextField(
                        cell, { v -> edit { ModelOps.setCell(it, r, c, v) } },
                        singleLine = true, modifier = Modifier.width(160.dp),
                    )
                }
            }
            TextButton(onClick = { edit { ModelOps.removeRow(it, r) } }) { Text("×") }
        }
    }
    OutlinedButton(onClick = { edit { ModelOps.addRow(it) } }) { Text("+ Row") }
}

// ─── External table: names + types only (data lives on the device) ──────────

@Composable
private fun ExternalColumnsEditor(view: ViewSpec, edit: ((ViewSpec) -> ViewSpec) -> Unit) {
    Text("Columns (scaffolded on the device when the file is missing)",
         style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    view.columns.forEachIndexed { col, name ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp)) {
            OutlinedTextField(
                name,
                { v ->
                    edit {
                        it.copy(columns = it.columns.mapIndexed { c, old ->
                            if (c == col) v else old
                        })
                    }
                },
                label = { Text("column ${col + 1}") }, singleLine = true,
                modifier = Modifier.width(200.dp),
            )
            ColTypePicker(
                view.colTypes.getOrElse(col) { ColType.Text },
                onPick = { t ->
                    edit {
                        val width = it.columns.size
                        it.copy(colTypes = (0 until width).map { c ->
                            if (c == col) t else it.colTypes.getOrElse(c) { ColType.Text }
                        })
                    }
                },
            )
            TextButton(onClick = {
                edit {
                    it.copy(
                        columns = it.columns.filterIndexed { c, _ -> c != col },
                        colTypes = it.colTypes.filterIndexed { c, _ -> c != col },
                    )
                }
            }) { Text("Remove") }
        }
    }
    OutlinedButton(onClick = {
        edit { it.copy(columns = it.columns + "New column",
                       colTypes = it.colTypes + ColType.Text) }
    }) { Text("+ Column") }
}

// ─── Checklist editor ────────────────────────────────────────────────────────

@Composable
private fun ChecklistEditor(view: ViewSpec, edit: ((ViewSpec) -> ViewSpec) -> Unit) {
    val list = ModelOps.firstChecklist(view) ?: BodyElement.Checklist(emptyList())
    Text("Items", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    list.items.forEachIndexed { i, item ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Checkbox(
                checked = item.state.equals("x", ignoreCase = true),
                onCheckedChange = { on ->
                    edit { ModelOps.setItem(it, i, item.copy(state = if (on) "X" else " ")) }
                },
            )
            OutlinedTextField(
                item.text,
                { v -> edit { ModelOps.setItem(it, i, item.copy(text = v)) } },
                singleLine = true, modifier = Modifier.width(320.dp),
            )
            TextButton(onClick = { edit { ModelOps.removeItem(it, i) } }) { Text("×") }
        }
    }
    OutlinedButton(onClick = { edit { ModelOps.addItem(it, "New item") } }) {
        Text("+ Item")
    }
}

// ─── Column-type picker ──────────────────────────────────────────────────────

@Composable
internal fun ColTypePicker(current: ColType, onPick: (ColType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(current.toToken()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(ColType.Text, ColType.Number, ColType.Date, ColType.Checkbox)
                .forEach { t ->
                    DropdownMenuItem(text = { Text(t.toToken()) },
                                     onClick = { onPick(t); open = false })
                }
            DropdownMenuItem(
                text = { Text("enum(…)") },
                onClick = {
                    onPick((current as? ColType.Enum) ?: ColType.Enum(listOf("A", "B")))
                    open = false
                },
            )
        }
    }
    if (current is ColType.Enum) {
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            current.options.joinToString(","),
            { v ->
                onPick(ColType.Enum(v.split(",").map(String::trim)
                                        .filter(String::isNotEmpty)
                                        .ifEmpty { listOf("A") }))
            },
            label = { Text("options") }, singleLine = true,
            modifier = Modifier.width(200.dp),
        )
    }
}
