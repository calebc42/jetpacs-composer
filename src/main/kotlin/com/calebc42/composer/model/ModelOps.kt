// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

/**
 * Pure edit operations over [AppSpec]. The editor UI is a thin shell
 * around these: every user gesture maps to one function producing a new
 * immutable spec, which the canonical writer then persists. Keeping
 * them here (and unit-tested) means the UI can stay dumb.
 *
 * Table operations act on the view's FIRST table body element — the
 * datasource by the format's definition; further tables are opaque
 * content.
 */
object ModelOps {

    // ─── Views ───────────────────────────────────────────────────────────

    fun addView(spec: AppSpec, title: String, kind: ViewKind): AppSpec {
        val body = when (kind) {
            ViewKind.TABLE -> listOf(
                BodyElement.Table(header = listOf("Name"), rows = emptyList()))
            ViewKind.CHECKLIST -> listOf(
                BodyElement.Checklist(emptyList()))
            ViewKind.RECORDS -> emptyList()
        }
        val view = ViewSpec(
            title = uniqueTitle(spec, title),
            kind = kind,
            order = ((spec.views.mapNotNull { it.order }.maxOrNull() ?: 0) + 10),
            colTypes = if (kind == ViewKind.RECORDS || kind == ViewKind.TABLE)
                listOf(ColType.Text) else emptyList(),
            schema = if (kind == ViewKind.RECORDS)
                listOf(SchemaField("ITEM", "Name")) else emptyList(),
            body = body,
        )
        return spec.copy(views = spec.views + view)
    }

    /** TITLE, made slug-unique against the existing views by suffixing. */
    private fun uniqueTitle(spec: AppSpec, title: String): String {
        val slugs = spec.views.map { it.name }.toSet()
        var candidate = title
        var n = 2
        while (ViewSpec(title = candidate).name in slugs) {
            candidate = "$title $n"; n++
        }
        return candidate
    }

    fun removeView(spec: AppSpec, index: Int): AppSpec =
        spec.copy(views = spec.views.filterIndexed { i, _ -> i != index })

    fun moveView(spec: AppSpec, index: Int, delta: Int): AppSpec {
        val target = index + delta
        if (target !in spec.views.indices) return spec
        val views = spec.views.toMutableList()
        val v = views.removeAt(index)
        views.add(target, v)
        return spec.copy(views = views)
    }

    fun updateView(spec: AppSpec, index: Int, transform: (ViewSpec) -> ViewSpec): AppSpec =
        spec.copy(views = spec.views.mapIndexed { i, v ->
            if (i == index) transform(v) else v
        })

    // ─── The view's datasource table ─────────────────────────────────────

    fun firstTable(view: ViewSpec): BodyElement.Table? =
        view.body.filterIsInstance<BodyElement.Table>().firstOrNull()

    private fun mapFirstTable(
        view: ViewSpec, transform: (BodyElement.Table) -> BodyElement.Table,
    ): ViewSpec {
        var done = false
        val body = view.body.map { el ->
            if (!done && el is BodyElement.Table) {
                done = true; transform(el)
            } else el
        }
        return view.copy(
            body = if (done) body
            else body + transform(BodyElement.Table(emptyList(), emptyList())))
    }

    fun setColumnName(view: ViewSpec, col: Int, name: String): ViewSpec =
        mapFirstTable(view) { t ->
            t.copy(header = t.header.mapIndexed { c, h -> if (c == col) name else h })
        }

    fun setColumnType(view: ViewSpec, col: Int, type: ColType): ViewSpec {
        val width = firstTable(view)?.header?.size ?: (col + 1)
        val types = (0 until width).map { c ->
            if (c == col) type else view.colTypes.getOrElse(c) { ColType.Text }
        }
        return view.copy(colTypes = types)
    }

    fun addColumn(view: ViewSpec, name: String): ViewSpec {
        val extended = mapFirstTable(view) { t ->
            BodyElement.Table(
                header = t.header + name,
                rows = t.rows.map { it + "" },
            )
        }
        val width = firstTable(extended)!!.header.size
        val types = (0 until width).map { c ->
            extended.colTypes.getOrElse(c) { ColType.Text }
        }
        return extended.copy(colTypes = types)
    }

    fun removeColumn(view: ViewSpec, col: Int): ViewSpec {
        val shrunk = mapFirstTable(view) { t ->
            BodyElement.Table(
                header = t.header.filterIndexed { c, _ -> c != col },
                rows = t.rows.map { r -> r.filterIndexed { c, _ -> c != col } },
            )
        }
        return shrunk.copy(
            colTypes = shrunk.colTypes.filterIndexed { c, _ -> c != col })
    }

    fun setCell(view: ViewSpec, row: Int, col: Int, value: String): ViewSpec =
        mapFirstTable(view) { t ->
            t.copy(rows = t.rows.mapIndexed { r, cells ->
                if (r == row)
                    cells.mapIndexed { c, old -> if (c == col) value else old }
                else cells
            })
        }

    fun addRow(view: ViewSpec): ViewSpec =
        mapFirstTable(view) { t ->
            t.copy(rows = t.rows + listOf(t.header.mapIndexed { c, _ ->
                if (view.colTypes.getOrElse(c) { ColType.Text } == ColType.Checkbox)
                    "[ ]" else ""
            }))
        }

    fun removeRow(view: ViewSpec, row: Int): ViewSpec =
        mapFirstTable(view) { t ->
            t.copy(rows = t.rows.filterIndexed { r, _ -> r != row })
        }

    // ─── The view's checklist ────────────────────────────────────────────

    fun firstChecklist(view: ViewSpec): BodyElement.Checklist? =
        view.body.filterIsInstance<BodyElement.Checklist>().firstOrNull()

    private fun mapFirstChecklist(
        view: ViewSpec, transform: (BodyElement.Checklist) -> BodyElement.Checklist,
    ): ViewSpec {
        var done = false
        val body = view.body.map { el ->
            if (!done && el is BodyElement.Checklist) {
                done = true; transform(el)
            } else el
        }
        return view.copy(
            body = if (done) body
            else body + transform(BodyElement.Checklist(emptyList())))
    }

    fun addItem(view: ViewSpec, text: String): ViewSpec =
        mapFirstChecklist(view) { it.copy(items = it.items + ChecklistItem(" ", text)) }

    fun setItem(view: ViewSpec, index: Int, item: ChecklistItem): ViewSpec =
        mapFirstChecklist(view) {
            it.copy(items = it.items.mapIndexed { i, old ->
                if (i == index) item else old
            })
        }

    fun removeItem(view: ViewSpec, index: Int): ViewSpec =
        mapFirstChecklist(view) {
            it.copy(items = it.items.filterIndexed { i, _ -> i != index })
        }

    // ─── Validation ──────────────────────────────────────────────────────

    data class Problem(val message: String, val viewIndex: Int? = null)

    /** Everything the closed format can still get wrong after the UI. */
    fun validate(spec: AppSpec): List<Problem> = buildList {
        val slugs = spec.views.map { it.name }
        slugs.groupBy { it }.filter { it.value.size > 1 }.keys.forEach {
            add(Problem("Two views slugify to \"$it\" — retitle one"))
        }
        spec.views.forEachIndexed { i, view ->
            if (view.kind == ViewKind.RECORDS) {
                if (view.schema.isEmpty())
                    add(Problem("A records view needs at least one schema field", i))
                if (view.schema.any { it.prop.isBlank() })
                    add(Problem("A schema field has no property name", i))
                val names = view.schema.map { it.prop.uppercase() }
                if (names.size != names.distinct().size)
                    add(Problem("Duplicate property in the schema", i))
                if (view.colTypes.size > view.schema.size && view.schema.isNotEmpty())
                    add(Problem("More field types than schema fields", i))
            }
            if (view.kind == ViewKind.TABLE) {
                val width = when {
                    view.source == null -> firstTable(view)?.header?.size ?: 0
                    else -> view.columns.size
                }
                if (view.colTypes.size > width && width > 0)
                    add(Problem("More column types than columns", i))
                if (view.source == null &&
                    firstTable(view)?.header?.any { it.isBlank() } == true)
                    add(Problem("A column has no name", i))
                if (view.source != null && view.columns.isEmpty())
                    add(Problem(
                        "External source without :COLUMNS: — it won't be " +
                            "scaffolded if the file is missing", i))
                view.colTypes.filterIsInstance<ColType.Enum>().forEach { e ->
                    if (e.options.any { it.isBlank() })
                        add(Problem("An enum option is blank", i))
                }
            }
        }
    }
}
