// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.project

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.TodoKeyword
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec
import com.calebc42.composer.org.OrgCodec

// ---------------------------------------------------------------------------
// Use-case presets
// ---------------------------------------------------------------------------

/** A canned use case that pre-fills the New-App wizard. */
enum class UseCase(
    val id: String,
    val label: String,
    val kind: ViewKind,
    val schema: List<SchemaField>,
    /** TABLE-only: column name→type pairs (ignored for records-type kinds). */
    val columns: List<Pair<String, ColType>>,
    /** null = inline data. */
    val source: SourceRef?,
    val groupBy: String? = null,
    val dateField: String? = null,
    val todoSequence: List<TodoKeyword> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String = "",
) {
    TASK_TRACKER(
        id = "tasks",
        label = "Task Tracker",
        kind = ViewKind.BOARD,
        schema = listOf(
            SchemaField.of("ITEM", "Name"),
            SchemaField.of("TODO"),
            SchemaField.of("PRIORITY"),
            SchemaField.of("DEADLINE"),
            SchemaField.of("TAGS"),
        ),
        columns = emptyList(),
        source = SourceRef.File("/sdcard/org/tasks.org"),
        groupBy = "TODO",
        todoSequence = listOf(
            TodoKeyword("TODO", false),
            TodoKeyword("NEXT", false),
            TodoKeyword("DOING", false),
            TodoKeyword("WAITING", false),
            TodoKeyword("DONE", true),
            TodoKeyword("CANCELLED", true),
        ),
        description = "Kanban board grouped by TODO state",
    ),

    NOTE_VAULT(
        id = "notes",
        label = "Note Vault",
        kind = ViewKind.NOTES,
        schema = listOf(
            SchemaField.of("ITEM", "Title"),
            SchemaField.of("TAGS"),
            SchemaField.of("CATEGORY"),
        ),
        columns = emptyList(),
        source = SourceRef.Dir("/sdcard/org/notes/"),
        description = "One-file-per-note vault",
    ),

    CONTACTS(
        id = "contacts",
        label = "Contacts",
        kind = ViewKind.RECORDS,
        schema = listOf(
            SchemaField.of("ITEM", "Name"),
            SchemaField.of("Phone"),
            SchemaField.of("Email"),
            SchemaField.of("Org"),
        ),
        columns = emptyList(),
        source = SourceRef.File("/sdcard/org/contacts.org"),
        description = "Contact list with custom properties",
    ),

    INVENTORY(
        id = "inventory",
        label = "Inventory",
        kind = ViewKind.TABLE,
        schema = emptyList(),
        columns = listOf(
            "Name" to ColType.Text,
            "Qty" to ColType.Number,
            "Location" to ColType.Text,
            "Notes" to ColType.Text,
        ),
        source = null,
        description = "Simple inline table",
    ),

    CALENDAR(
        id = "planner",
        label = "Calendar",
        kind = ViewKind.CALENDAR,
        schema = listOf(
            SchemaField.of("ITEM", "Event"),
            SchemaField.of("SCHEDULED"),
            SchemaField.of("DEADLINE"),
            SchemaField.of("PRIORITY"),
        ),
        columns = emptyList(),
        source = SourceRef.File("/sdcard/org/agenda.org"),
        dateField = "SCHEDULED",
        todoSequence = listOf(
            TodoKeyword("TODO", false),
            TodoKeyword("NEXT", false),
            TodoKeyword("DOING", false),
            TodoKeyword("WAITING", false),
            TodoKeyword("DONE", true),
            TodoKeyword("CANCELLED", true),
        ),
        description = "Agenda / calendar view keyed on SCHEDULED",
    ),
}
// ---------------------------------------------------------------------------
// Template builder
// ---------------------------------------------------------------------------

/** The bundled template gallery + the wizard's spec builder. */
object Templates {

    val names = listOf("inventory", "checklist", "contacts", "hello-world")

    fun load(name: String): AppSpec {
        val text = Templates::class.java
            .getResourceAsStream("/templates/$name.org")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("template $name missing from the classpath")
        return OrgCodec.parse(text)
    }

    // -- Use-case shortcut ---------------------------------------------------

    /** Build an [AppSpec] from a canned [UseCase] preset. */
    fun buildFromUseCase(useCase: UseCase): AppSpec {
        return build(
            id = useCase.id,
            label = useCase.label,
            icon = null,
            viewTitle = useCase.label,
            kind = useCase.kind,
            columns = useCase.columns,
            schema = useCase.schema,
            backendPath = null,     // source is passed directly
            source = useCase.source,
            groupBy = useCase.groupBy,
            dateField = useCase.dateField,
            todoSequence = useCase.todoSequence,
            tags = useCase.tags,
        )
    }

    // -- General builder -----------------------------------------------------

    /** Whether [kind] uses a `:SCHEMA:` rather than a table header row. */
    private fun isRecordsType(kind: ViewKind): Boolean = kind in listOf(
        ViewKind.RECORDS, ViewKind.NOTES, ViewKind.BOARD,
        ViewKind.CALENDAR, ViewKind.GALLERY, ViewKind.TREE,
    )

    /**
     * The data-first wizard: columns in, a working one-view app out.
     *
     * For TABLE / CHECKLIST kinds the [columns] list drives the header row.
     * For records-type kinds (RECORDS, NOTES, BOARD, CALENDAR, GALLERY) the
     * [schema] list drives the `:SCHEMA:` drawer instead.
     *
     * [backendPath] is a shorthand for a [SourceRef.File]; pass [source]
     * directly when you need a [SourceRef.Dir] or heading-scoped file.
     */
    fun build(
        id: String,
        label: String,
        icon: String?,
        viewTitle: String,
        kind: ViewKind = ViewKind.TABLE,
        columns: List<Pair<String, ColType>> = emptyList(),
        schema: List<SchemaField> = emptyList(),
        backendPath: String? = null,
        source: SourceRef? = null,
        groupBy: String? = null,
        dateField: String? = null,
        todoSequence: List<TodoKeyword> = emptyList(),
        tags: List<String> = emptyList(),
    ): AppSpec {
        val resolvedSource = source
            ?: backendPath?.let { SourceRef.File(it, viewTitle) }

        val view = if (isRecordsType(kind)) {
            // Records-type: use schema fields
            ViewSpec(
                title = viewTitle,
                kind = kind,
                order = 10,
                source = resolvedSource,
                schema = schema,
                groupBy = groupBy,
                dateField = dateField,
            )
        } else {
            // TABLE / CHECKLIST: use column header + inline body
            require(columns.isNotEmpty()) { "at least one column for TABLE/CHECKLIST" }
            val colNames = columns.map { it.first }
            val colTypes = columns.map { it.second }
            if (resolvedSource == null) {
                ViewSpec(
                    title = viewTitle,
                    kind = kind,
                    order = 10,
                    colTypes = colTypes,
                    body = listOf(BodyElement.Table(header = colNames, rows = emptyList())),
                )
            } else {
                ViewSpec(
                    title = viewTitle,
                    kind = kind,
                    order = 10,
                    source = resolvedSource,
                    colTypes = colTypes,
                    columns = colNames,
                )
            }
        }

        return AppSpec(
            id = id,
            label = label,
            icon = icon,
            todoSequence = todoSequence,
            tags = tags,
            views = listOf(view),
        )
    }
}
