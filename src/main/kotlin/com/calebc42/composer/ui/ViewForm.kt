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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
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
import com.calebc42.composer.model.ActionDef
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ChecklistItem
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.OrgBuiltin
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.TodoKeyword
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewNav
import com.calebc42.composer.model.ViewSpec

private typealias ViewEdit = ((ViewSpec) -> ViewSpec) -> Unit
private typealias ViewTextEdit = (String, (ViewSpec) -> ViewSpec) -> Unit

// ─── App form ────────────────────────────────────────────────────────────────

@Composable
fun AppForm(session: EditorSession) {
    val spec = session.spec
    Text("App", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            spec.id, { v -> session.update("app.id") { it.copy(id = v.lowercase()) } },
            label = { Text("id (slug)") }, singleLine = true,
            isError = !com.calebc42.composer.model.AppSpec.ID_RE.matches(spec.id),
            modifier = Modifier.width(200.dp),
        )
        OutlinedTextField(
            spec.label.orEmpty(),
            { v -> session.update("app.label") { it.copy(label = v.ifBlank { null }) } },
            label = { Text("launcher label") }, singleLine = true,
            modifier = Modifier.width(220.dp),
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        var showAppIconPicker by remember { mutableStateOf(false) }
        OutlinedTextField(
            spec.icon.orEmpty(),
            { v -> session.update("app.icon") { it.copy(icon = v.ifBlank { null }) } },
            label = { Text("icon (Material name)") }, singleLine = true,
            modifier = Modifier.width(200.dp),
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showAppIconPicker = true }) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Edit, // palette-like
                        contentDescription = "Pick icon"
                    )
                }
            }
        )
        if (showAppIconPicker) {
            IconPicker(
                onIconSelected = { name ->
                    session.update { spec -> spec.copy(icon = name) }
                    showAppIconPicker = false
                },
                onDismiss = { showAppIconPicker = false }
            )
        }
        OutlinedTextField(
            spec.order?.toString().orEmpty(),
            { v -> session.update("app.order") { it.copy(order = v.toIntOrNull()) } },
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

    // ─── TODO Keywords ───────────────────────────────────────────────────
    Spacer(Modifier.height(16.dp))
    Text("TODO Keywords", style = MaterialTheme.typography.titleMedium)
    Text(
        "Keywords before the separator are active states; after are done states. " +
            "Emitted as #+TODO: in the file.",
        style = MaterialTheme.typography.bodySmall,
    )
    Spacer(Modifier.height(8.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val activeKws = spec.todoSequence.filter { !it.isDone }
        val doneKws = spec.todoSequence.filter { it.isDone }
        activeKws.forEachIndexed { i, kw ->
            OutlinedTextField(
                kw.keyword,
                { v ->
                    session.update("app.todo.active.$i") {
                        it.copy(todoSequence = it.todoSequence.map { tk ->
                            if (tk === kw) tk.copy(keyword = v.trim().uppercase()) else tk
                        })
                    }
                },
                singleLine = true, modifier = Modifier.width(100.dp),
            )
            TextButton(onClick = {
                session.update { it.copy(todoSequence = it.todoSequence.filter { tk -> tk !== kw }) }
            }) { Text("×") }
        }
        OutlinedButton(onClick = {
            session.update {
                it.copy(todoSequence = it.todoSequence + TodoKeyword("NEW", isDone = false))
            }
        }) { Text("+") }

        Text(" | ", style = MaterialTheme.typography.titleMedium)

        doneKws.forEachIndexed { i, kw ->
            OutlinedTextField(
                kw.keyword,
                { v ->
                    session.update("app.todo.done.$i") {
                        it.copy(todoSequence = it.todoSequence.map { tk ->
                            if (tk === kw) tk.copy(keyword = v.trim().uppercase()) else tk
                        })
                    }
                },
                singleLine = true, modifier = Modifier.width(100.dp),
            )
            TextButton(onClick = {
                session.update { it.copy(todoSequence = it.todoSequence.filter { tk -> tk !== kw }) }
            }) { Text("×") }
        }
        OutlinedButton(onClick = {
            session.update {
                it.copy(todoSequence = it.todoSequence + TodoKeyword("DONE", isDone = true))
            }
        }) { Text("+") }
    }

    // ─── Tags ────────────────────────────────────────────────────────────
    Spacer(Modifier.height(16.dp))
    Text("Tags", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        spec.tags.joinToString(", "),
        { v ->
            session.update("app.tags") {
                it.copy(tags = v.split(",").map(String::trim).filter(String::isNotEmpty))
            }
        },
        label = { Text("tags (comma-separated)") }, singleLine = true,
        modifier = Modifier.width(400.dp),
    )
}

// ─── View form ───────────────────────────────────────────────────────────────

@Composable
fun ViewForm(session: EditorSession, index: Int) {
    val spec = session.spec
    val view = spec.views[index]
    val referenceTargets = spec.views.filter {
        it.kind == ViewKind.RECORDS || it.kind == ViewKind.NOTES
    }
    fun edit(transform: (ViewSpec) -> ViewSpec) =
        session.update { ModelOps.updateView(it, index, transform) }
    fun editText(key: String, transform: (ViewSpec) -> ViewSpec) =
        session.update("view.$index.$key") { ModelOps.updateView(it, index, transform) }

    Text("View — ${view.title}", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            view.title, { v -> editText("title") { it.copy(title = v) } },
            label = { Text("title (tab label)") }, singleLine = true,
            modifier = Modifier.width(220.dp),
        )
        var showViewIconPicker by remember { mutableStateOf(false) }
        OutlinedTextField(
            view.icon.orEmpty(),
            { v -> editText("icon") { it.copy(icon = v.ifBlank { null }) } },
            label = { Text("tab icon") }, singleLine = true,
            modifier = Modifier.width(200.dp),
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showViewIconPicker = true }) {
                    androidx.compose.material3.Icon(
                        Icons.Default.Edit,
                        contentDescription = "Pick icon"
                    )
                }
            }
        )
        if (showViewIconPicker) {
            IconPicker(
                onIconSelected = { 
                    edit { spec -> spec.copy(icon = it) }
                    showViewIconPicker = false
                },
                onDismiss = { showViewIconPicker = false }
            )
        }
        OutlinedTextField(
            view.order?.toString().orEmpty(),
            { v -> editText("order") { it.copy(order = v.toIntOrNull()) } },
            label = { Text("tab order") }, singleLine = true,
            modifier = Modifier.width(120.dp),
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kind:", style = MaterialTheme.typography.bodyMedium)

        var expanded by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(view.kind.name)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                ViewKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.name) },
                        onClick = {
                            edit {
                                val schema = if (kind in listOf(ViewKind.RECORDS, ViewKind.NOTES, ViewKind.BOARD, ViewKind.CALENDAR, ViewKind.GALLERY, ViewKind.TREE)) {
                                    it.schema.ifEmpty { listOf(SchemaField("ITEM", "Name")) }
                                } else it.schema
                                it.copy(kind = kind, schema = schema)
                            }
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Placement:", style = MaterialTheme.typography.bodyMedium)

        val placement = when {
            view.group != null -> "Group"
            view.nav == ViewNav.DRAWER -> "Drawer"
            else -> "Bottom tab"
        }
        var placeExpanded by remember { mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            OutlinedButton(onClick = { placeExpanded = true }) {
                Text(placement)
            }
            DropdownMenu(expanded = placeExpanded,
                         onDismissRequest = { placeExpanded = false }) {
                DropdownMenuItem(text = { Text("Bottom tab") }, onClick = {
                    edit { it.copy(nav = ViewNav.TAB, group = null) }
                    placeExpanded = false
                })
                DropdownMenuItem(text = { Text("Drawer (hamburger)") }, onClick = {
                    edit { it.copy(nav = ViewNav.DRAWER, group = null) }
                    placeExpanded = false
                })
                DropdownMenuItem(text = { Text("Group (tabbed)…") }, onClick = {
                    edit { it.copy(nav = ViewNav.TAB, group = it.group ?: "Group") }
                    placeExpanded = false
                })
            }
        }
        if (view.group != null) {
            OutlinedTextField(
                view.group,
                { v -> editText("group") { it.copy(group = v.ifBlank { null }) } },
                label = { Text("group name (shared destination)") }, singleLine = true,
                modifier = Modifier.width(240.dp),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    SourceEditor(view, ::edit, ::editText)

    Spacer(Modifier.height(16.dp))
    when {
        view.kind == ViewKind.CHECKLIST -> ChecklistEditor(view, ::edit, ::editText)
        view.kind in listOf(ViewKind.RECORDS, ViewKind.NOTES, ViewKind.BOARD, ViewKind.CALENDAR, ViewKind.GALLERY, ViewKind.TREE) -> {
            RecordsSchemaEditor(spec, view, ::edit, ::editText)
            Spacer(Modifier.height(16.dp))
            ActionEditor(
                actions = view.actions,
                onUpdate = { newActions, coalesceKey ->
                    if (coalesceKey == null) edit { it.copy(actions = newActions) }
                    else editText("actions.$coalesceKey") { it.copy(actions = newActions) }
                },
            )
        }
        view.source == null -> InlineTableEditor(view, referenceTargets, ::edit, ::editText)
        else -> ExternalColumnsEditor(view, referenceTargets, ::edit, ::editText)
    }
}

// ─── Records: schema + filter (the data lives on the device) ────────────────

@Composable
private fun RecordsSchemaEditor(
    spec: AppSpec,
    view: ViewSpec,
    edit: ViewEdit,
    editText: ViewTextEdit,
) {
    Text("Schema — fields of each record",
         style = MaterialTheme.typography.titleMedium)
    Text("A record is a heading with a property drawer. Special names — " +
             "ITEM (title), TODO, DEADLINE, SCHEDULED, PRIORITY — run org's " +
             "own machinery; anything else is a drawer property. A PROP_ALL " +
             "declaration in the data file supplies enum choices and wins " +
             "over the type here.",
         style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))

    if (view.source is SourceRef.File) {
        OutlinedButton(onClick = {
            val file = java.io.File(view.source.file)
            if (file.exists() && file.isFile) {
                val content = file.readText(Charsets.UTF_8)
                val (schema, types) = ModelOps.inferSchemaFromOrgContent(content)
                if (schema.isNotEmpty()) {
                    edit { it.copy(schema = schema, colTypes = types) }
                }
            }
        }, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("🪄 Infer Schema from File")
        }
    }

    view.schema.forEachIndexed { i, field ->
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 2.dp)) {
            OutlinedTextField(
                field.prop,
                { v ->
                    editText("schema.$i.property") {
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
                    editText("schema.$i.label") {
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
                referenceTargets = spec.views.filter {
                    it.kind == ViewKind.RECORDS || it.kind == ViewKind.NOTES
                },
                onPick = { t ->
                    edit {
                        val width = it.schema.size
                        it.copy(colTypes = (0 until width).map { c ->
                            if (c == i) t else it.colTypes.getOrElse(c) { ColType.Text }
                        })
                    }
                },
                onTyping = { t ->
                    editText("schema.$i.type") {
                        val width = it.schema.size
                        it.copy(colTypes = (0 until width).map { c ->
                            if (c == i) t else it.colTypes.getOrElse(c) { ColType.Text }
                        })
                    }
                },
            )
            TextButton(
                onClick = { edit { ModelOps.moveSchemaField(it, i, -1) } },
                enabled = i > 0,
            ) { Text("↑") }
            TextButton(
                onClick = { edit { ModelOps.moveSchemaField(it, i, 1) } },
                enabled = i < view.schema.lastIndex,
            ) { Text("↓") }
            TextButton(onClick = {
                edit {
                    it.copy(schema = it.schema.filterIndexed { j, _ -> j != i },
                            colTypes = it.colTypes.filterIndexed { j, _ -> j != i })
                }
            }, enabled = view.schema.size > 1) { Text("Remove") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            edit { it.copy(schema = it.schema + SchemaField("NewProp"),
                           colTypes = it.colTypes + ColType.Text) }
        }) { Text("+ Field") }

        // "+ Org Builtin" dropdown — only shows builtins not already in the schema
        var builtinExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { builtinExpanded = true }) {
                Text("+ Org Builtin")
            }
            DropdownMenu(
                expanded = builtinExpanded,
                onDismissRequest = { builtinExpanded = false },
            ) {
                val existing = view.schema.map { it.prop }.toSet()
                SchemaField.ORG_BUILTINS
                    .filter { it.key !in existing }
                    .forEach { (name, builtin) ->
                        DropdownMenuItem(
                            text = { Text("$name — ${builtin.description}") },
                            onClick = {
                                edit {
                                    it.copy(
                                        schema = it.schema + SchemaField(name),
                                        colTypes = it.colTypes + builtin.defaultType,
                                    )
                                }
                                builtinExpanded = false
                            },
                        )
                    }
            }
        }
    }

    Spacer(Modifier.height(12.dp))
    var showExpressionEditor by remember { mutableStateOf(false) }
    OutlinedTextField(
        view.filter.orEmpty(),
        { v -> editText("filter") { it.copy(filter = v.trim().ifBlank { null }) } },
        label = { Text("filter (guided device terms or raw org-ql)") },
        singleLine = true, modifier = Modifier.width(420.dp),
        trailingIcon = {
            androidx.compose.material3.IconButton(onClick = { showExpressionEditor = true }) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Default.Edit,
                    contentDescription = "Edit filter"
                )
            }
        }
    )
    if (showExpressionEditor) {
        ExpressionDialog(
            initialValue = view.filter.orEmpty(),
            viewKind = view.kind,
            properties = view.schema.map { it.prop },
            todoKeywords = spec.todoSequence.map { it.keyword }
                .ifEmpty { listOf("TODO", "DONE") },
            tags = spec.tags,
            onSave = { v ->
                edit { it.copy(filter = v.trim().ifBlank { null }) }
                showExpressionEditor = false
            },
            onDismiss = { showExpressionEditor = false }
        )
    }

    // ─── View-kind-specific configuration ────────────────────────────────
    Spacer(Modifier.height(12.dp))
    when (view.kind) {
        ViewKind.BOARD -> {
            var groupByExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Group by:", style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { groupByExpanded = true }) {
                        Text(view.groupBy ?: "(select)")
                    }
                    DropdownMenu(
                        expanded = groupByExpanded,
                        onDismissRequest = { groupByExpanded = false },
                    ) {
                        val enumFields = view.schema.zip(view.colTypes).filter { (field, type) ->
                            field.prop == "TODO" || type is ColType.Enum
                        }.map { it.first }
                        if (enumFields.isEmpty()) {
                            DropdownMenuItem(text = { Text("No enum fields available") }, onClick = {})
                        } else {
                            enumFields.forEach { field ->
                                DropdownMenuItem(
                                    text = { Text(field.prop) },
                                    onClick = {
                                        edit { it.copy(groupBy = field.prop) }
                                        groupByExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        ViewKind.CALENDAR -> {
            var dateFieldExpanded by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Date field:", style = MaterialTheme.typography.bodyMedium)
                Box {
                    OutlinedButton(onClick = { dateFieldExpanded = true }) {
                        Text(view.dateField ?: "(select)")
                    }
                    DropdownMenu(
                        expanded = dateFieldExpanded,
                        onDismissRequest = { dateFieldExpanded = false },
                    ) {
                        view.schema.forEach { field ->
                            DropdownMenuItem(
                                text = { Text(field.prop) },
                                onClick = {
                                    edit { it.copy(dateField = field.prop) }
                                    dateFieldExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
        ViewKind.GALLERY -> {
            OutlinedTextField(
                view.imageField.orEmpty(),
                { v -> editText("imageField") {
                    it.copy(imageField = v.trim().ifBlank { null })
                } },
                label = { Text("Image field (schema property name)") },
                singleLine = true,
                modifier = Modifier.width(260.dp),
            )
        }
        else -> {} // no extra config needed
    }
}

@Composable
private fun SourceEditor(view: ViewSpec, edit: ViewEdit, editText: ViewTextEdit) {
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
                                ?: SourceRef.File("/sdcard/org/data.org", it.title))
                        }
                    })
        Text("external org file")
    }
    when (val source = view.source) {
        is SourceRef.File -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                source.file,
                { v -> editText("source.file") {
                    it.copy(source = source.copy(file = v))
                } },
                label = { Text("device path") }, singleLine = true,
                modifier = Modifier.width(320.dp),
            )
            OutlinedTextField(
                source.heading.orEmpty(),
                { v -> editText("source.heading") {
                    it.copy(source = source.copy(heading = v.ifBlank { null }))
                } },
                label = { Text("heading (optional)") }, singleLine = true,
                modifier = Modifier.width(220.dp),
            )
        }
        is SourceRef.Dir -> OutlinedTextField(
            source.dir,
            { v -> editText("source.dir") { it.copy(source = SourceRef.Dir(v)) } },
            label = { Text("note vault directory") }, singleLine = true,
            modifier = Modifier.width(320.dp),
        )
        null -> {}
    }
    if (view.source != null) {
        if (ModelOps.isRecordsType(view.kind)) {
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
private fun InlineTableEditor(
    view: ViewSpec,
    referenceTargets: List<ViewSpec>,
    edit: ViewEdit,
    editText: ViewTextEdit,
) {
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
                name, { v -> editText("column.$col.name") {
                    ModelOps.setColumnName(it, col, v)
                } },
                label = { Text("column ${col + 1}") }, singleLine = true,
                modifier = Modifier.width(200.dp),
            )
            ColTypePicker(
                view.colTypes.getOrElse(col) { ColType.Text },
                referenceTargets = referenceTargets,
                onPick = { t -> edit { ModelOps.setColumnType(it, col, t) } },
                onTyping = { t -> editText("column.$col.type") {
                    ModelOps.setColumnType(it, col, t)
                } },
            )
            TextButton(
                onClick = { edit { ModelOps.moveColumn(it, col, -1) } },
                enabled = col > 0,
            ) { Text("↑") }
            TextButton(
                onClick = { edit { ModelOps.moveColumn(it, col, 1) } },
                enabled = col < table.header.lastIndex,
            ) { Text("↓") }
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
                        cell, { v -> editText("cell.$r.$c") {
                            ModelOps.setCell(it, r, c, v)
                        } },
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
private fun ExternalColumnsEditor(
    view: ViewSpec,
    referenceTargets: List<ViewSpec>,
    edit: ViewEdit,
    editText: ViewTextEdit,
) {
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
                    editText("column.$col.name") {
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
                referenceTargets = referenceTargets,
                onPick = { t ->
                    edit {
                        val width = it.columns.size
                        it.copy(colTypes = (0 until width).map { c ->
                            if (c == col) t else it.colTypes.getOrElse(c) { ColType.Text }
                        })
                    }
                },
                onTyping = { t ->
                    editText("column.$col.type") {
                        val width = it.columns.size
                        it.copy(colTypes = (0 until width).map { c ->
                            if (c == col) t else it.colTypes.getOrElse(c) { ColType.Text }
                        })
                    }
                },
            )
            TextButton(
                onClick = { edit { ModelOps.moveColumn(it, col, -1) } },
                enabled = col > 0,
            ) { Text("↑") }
            TextButton(
                onClick = { edit { ModelOps.moveColumn(it, col, 1) } },
                enabled = col < view.columns.lastIndex,
            ) { Text("↓") }
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
private fun ChecklistEditor(view: ViewSpec, edit: ViewEdit, editText: ViewTextEdit) {
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
                { v -> editText("item.$i.text") {
                    ModelOps.setItem(it, i, item.copy(text = v))
                } },
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
internal fun ColTypePicker(
    current: ColType,
    referenceTargets: List<ViewSpec> = emptyList(),
    onPick: (ColType) -> Unit,
    onTyping: (ColType) -> Unit = onPick,
) {
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
            if (referenceTargets.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("ref(…)") },
                    onClick = {
                        onPick((current as? ColType.Ref)
                            ?: ColType.Ref(referenceTargets.first().name))
                        open = false
                    },
                )
            }
        }
    }
    if (current is ColType.Enum) {
        Spacer(Modifier.width(4.dp))
        OutlinedTextField(
            current.options.joinToString(","),
            { v ->
                onTyping(ColType.Enum(v.split(",").map(String::trim)
                                        .filter(String::isNotEmpty)
                                        .ifEmpty { listOf("A") }))
            },
            label = { Text("options") }, singleLine = true,
            modifier = Modifier.width(200.dp),
        )
    } else if (current is ColType.Ref) {
        Spacer(Modifier.width(4.dp))
        var targetOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { targetOpen = true }) {
                Text(referenceTargets.find { it.name == current.targetView }?.title
                    ?: current.targetView)
            }
            DropdownMenu(expanded = targetOpen, onDismissRequest = { targetOpen = false }) {
                referenceTargets.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.title) },
                        onClick = {
                            onPick(ColType.Ref(target.name))
                            targetOpen = false
                        },
                    )
                }
            }
        }
    }
}
