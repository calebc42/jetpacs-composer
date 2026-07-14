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
            ViewKind.RECORDS, ViewKind.NOTES, ViewKind.BOARD, ViewKind.CALENDAR, ViewKind.GALLERY, ViewKind.TREE, ViewKind.DASHBOARD, ViewKind.GANTT, ViewKind.UNKNOWN -> emptyList()
        }
        val isRecords = isRecordsType(kind)
        val schema = when (kind) {
            ViewKind.BOARD -> listOf(
                SchemaField("ITEM", "Name"),
                SchemaField("TODO", "Status"),
            )
            ViewKind.CALENDAR -> listOf(
                SchemaField("ITEM", "Name"),
                SchemaField("SCHEDULED", "Date"),
            )
            ViewKind.GALLERY -> listOf(
                SchemaField("ITEM", "Name"),
                SchemaField("IMAGE", "Image"),
            )
            ViewKind.DASHBOARD -> listOf(
                SchemaField("ITEM", "Name"),
                SchemaField("AMOUNT", "Amount"),
            )
            ViewKind.GANTT -> listOf(
                SchemaField("ITEM", "Name"),
                SchemaField("TODO", "Progress"),
                SchemaField("SCHEDULED", "Start"),
                SchemaField("DEADLINE", "End"),
            )
            else -> if (isRecords) listOf(SchemaField("ITEM", "Name")) else emptyList()
        }
        val view = ViewSpec(
            title = uniqueTitle(spec, title),
            kind = kind,
            order = ((spec.views.mapNotNull { it.order }.maxOrNull() ?: 0) + 10),
            colTypes = if (kind == ViewKind.TABLE) {
                listOf(ColType.Text)
            } else {
                schema.map { field ->
                    SchemaField.ORG_BUILTINS[field.prop]?.defaultType ?: ColType.Text
                }
            },
            schema = schema,
            groupBy = if (kind == ViewKind.BOARD) "TODO" else null,
            dateField = if (kind == ViewKind.CALENDAR) "SCHEDULED" else null,
            metrics = if (kind == ViewKind.DASHBOARD)
                listOf(DashboardMetric(AggregateOp.COUNT)) else emptyList(),
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

    /**
     * Give a document that references a pack its `#+JETPACS_PACK:`
     * declaration when it has none: the first referenced pack id, with the
     * installed manifest's version as the minimum when one is available.
     * A document with an explicit declaration is left alone; so is one
     * with no pack references. The editor runs this after every edit so a
     * picker-inserted (or hand-typed) reference always exports declared.
     */
    fun autoDeclarePack(spec: AppSpec, packs: PackRegistry): AppSpec {
        if (spec.pack != null) return spec
        val referenced = spec.views.firstNotNullOfOrNull { view ->
            (view.source as? SourceRef.Pack)?.packId
                ?: view.actions.filterIsInstance<ActionDef.PackAction>()
                    .firstOrNull()?.packId
        } ?: return spec
        if (!AppSpec.ID_RE.matches(referenced)) return spec
        return spec.copy(
            pack = PackRef(referenced, packs.byId(referenced)?.pack_version))
    }

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

    private fun <T> moveItem(items: List<T>, index: Int, target: Int): List<T> {
        if (index !in items.indices || target !in items.indices || index == target)
            return items
        val moved = items.toMutableList()
        val item = moved.removeAt(index)
        moved.add(target, item)
        return moved
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

    /**
     * Move one table column while keeping its positional type and every row's
     * cell at the same logical column. Inline tables move their first table;
     * external tables move the declared scaffold columns.
     */
    fun moveColumn(view: ViewSpec, col: Int, delta: Int): ViewSpec {
        val width = if (view.source == null) {
            firstTable(view)?.header?.size ?: return view
        } else {
            view.columns.size
        }
        val target = col + delta
        if (col !in 0 until width || target !in 0 until width) return view

        val types = if (view.colTypes.size >= width) view.colTypes else
            view.colTypes + List(width - view.colTypes.size) { ColType.Text }
        return if (view.source == null) {
            mapFirstTable(view) { table ->
                table.copy(
                    header = moveItem(table.header, col, target),
                    rows = table.rows.map { row ->
                        val aligned = if (row.size >= width) row else
                            row + List(width - row.size) { "" }
                        moveItem(aligned, col, target)
                    },
                )
            }.copy(colTypes = moveItem(types, col, target))
        } else {
            view.copy(
                columns = moveItem(view.columns, col, target),
                colTypes = moveItem(types, col, target),
            )
        }
    }

    /** Move a records schema field and its positional COLTYPE together. */
    fun moveSchemaField(view: ViewSpec, field: Int, delta: Int): ViewSpec {
        val target = field + delta
        if (field !in view.schema.indices || target !in view.schema.indices) return view
        val width = view.schema.size
        val types = if (view.colTypes.size >= width) view.colTypes else
            view.colTypes + List(width - view.colTypes.size) { ColType.Text }
        return view.copy(
            schema = moveItem(view.schema, field, target),
            colTypes = moveItem(types, field, target),
        )
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

    enum class Severity { Error, Warning, Info }

    data class Problem(
        val message: String,
        val viewIndex: Int? = null,
        val severity: Severity = Severity.Error,
    )

    /**
     * The native node a view kind prefers, when the runtime has a widget
     * fallback for its absence. Grounded in the runtime's actual
     * `jetpacs-node-supported-p` gates: dashboards fall back from `chart`,
     * calendars from `month_grid`. Used for advisory warnings only.
     */
    private val KIND_PREFERRED_NODES = mapOf(
        ViewKind.DASHBOARD to "chart",
        ViewKind.CALENDAR to "month_grid",
    )

    /**
     * Everything the closed format can still get wrong after the UI.
     *
     * [nodeTypes], when given (the vendored contract.json's or a live
     * device's vocabulary), adds per-view *warnings* for kinds whose
     * preferred native node is missing — the runtime falls back, nothing
     * breaks. [packs], when given, adds *warnings* for `pack:` references
     * that no installed manifest can serve. Both are permissive when
     * absent (null) — validation stays advisory across connections.
     */
    fun validate(
        spec: AppSpec,
        nodeTypes: Set<String>? = null,
        packs: PackRegistry? = null,
    ): List<Problem> = buildList {
        if (spec.views.isEmpty())
            add(Problem("An app needs at least one view"))
        if (spec.inbox != null && spec.inbox.isBlank())
            add(Problem("Quick-capture inbox cannot be blank"))
        if (spec.inbox?.replace('\\', '/')?.endsWith('/') == true)
            add(Problem("Quick-capture inbox must be an org file, not a directory"))
        if (spec.pack == null && spec.usesPackFeatures())
            add(Problem(
                "This app uses pack: references but declares no " +
                    "#+JETPACS_PACK: — declare the pack (id + minimum " +
                    "version) so devices know what to install",
                severity = Severity.Warning,
            ))
        packs?.let { registry ->
            spec.pack?.let { declared ->
                if (registry.byId(declared.packId) == null)
                    add(Problem(
                        "No installed pack manifest for the declared pack " +
                            "\"${declared.packId}\"",
                        severity = Severity.Warning,
                    ))
            }
        }
        val slugs = spec.views.map { it.name }
        val liveViews = slugs.toSet()
        slugs.groupBy { it }.filter { it.value.size > 1 }.keys.forEach {
            add(Problem("Two views slugify to \"$it\" — retitle one"))
        }
        spec.views.forEachIndexed { i, view ->
            val isRecords = isRecordsType(view.kind)
            if (isRecords) {
                if (view.schema.isEmpty())
                    add(Problem("A ${view.kind.name.lowercase()} view needs at least one schema field", i))
                if (view.schema.any { it.prop.isBlank() })
                    add(Problem("A schema field has no property name", i))
                val names = view.schema.map { it.prop.uppercase() }
                if (names.size != names.distinct().size)
                    add(Problem("Duplicate property in the schema", i))
                if (view.colTypes.size > view.schema.size && view.schema.isNotEmpty())
                    add(Problem(
                        "More field types than schema fields",
                        i,
                        Severity.Warning,
                    ))
                if (view.kind == ViewKind.NOTES && view.source == null)
                    add(Problem("A notes view needs an external source", i))

                fun validateSchemaReference(value: String?, label: String) {
                    if (value != null && view.schema.none {
                            it.prop.equals(value, ignoreCase = true)
                        }) {
                        add(Problem(
                            "$label \"$value\" is not a field in this view's schema",
                            i,
                        ))
                    }
                }
                validateSchemaReference(
                    view.groupBy ?: if (view.kind == ViewKind.BOARD) "TODO" else null,
                    "Group-by field",
                )
                validateSchemaReference(
                    view.dateField ?: if (view.kind == ViewKind.CALENDAR) "DEADLINE" else null,
                    "Date field",
                )
                validateSchemaReference(
                    view.imageField ?: if (view.kind == ViewKind.GALLERY) "IMAGE" else null,
                    "Image field",
                )
                val filterResult = FilterQuery.parse(view.filter.orEmpty(), view.kind)
                if (filterResult is FilterQuery.ParseResult.Invalid)
                    add(Problem("Malformed filter: ${filterResult.message}", i))
            }
            if (view.kind == ViewKind.TABLE) {
                val width = when {
                    view.source == null -> firstTable(view)?.header?.size ?: 0
                    else -> view.columns.size
                }
                if (view.colTypes.size > width && width > 0)
                    add(Problem(
                        "More column types than columns",
                        i,
                        Severity.Warning,
                    ))
                if (view.source == null &&
                    firstTable(view)?.header?.any { it.isBlank() } == true)
                    add(Problem("A column has no name", i))
                if (view.source != null && view.columns.isEmpty())
                    add(Problem(
                        "External source without :COLUMNS: — it won't be " +
                            "scaffolded if the file is missing",
                        i,
                        Severity.Warning,
                    ))
            }

            when (val source = view.source) {
                is SourceRef.File -> if (source.file.isBlank())
                    add(Problem("Source file cannot be blank", i))
                is SourceRef.Dir -> if (source.dir.isBlank())
                    add(Problem("Source directory cannot be blank", i))
                is SourceRef.Pack -> if (source.source.isBlank() || source.packId.isBlank())
                    add(Problem("Pack source cannot be blank", i))
                is SourceRef.Unknown -> add(Problem(
                    "Source \"${source.raw}\" uses a scheme this composer doesn't " +
                        "know — kept as written, but the view will be unavailable " +
                        "on today's runtimes",
                    i,
                    Severity.Warning,
                ))
                null -> {}
            }
            nodeTypes?.let { supported ->
                KIND_PREFERRED_NODES[view.kind]?.let { node ->
                    if (node !in supported)
                        add(Problem(
                            "The companion contract lacks the \"$node\" node — " +
                                "this ${view.kind.name.lowercase()} view will " +
                                "render via its widget fallback",
                            i,
                            Severity.Warning,
                        ))
                }
            }
            packs?.let { registry ->
                fun checkPackRef(packId: String, kind: String, name: String,
                                 present: Boolean) {
                    val manifest = registry.byId(packId)
                    when {
                        manifest == null -> add(Problem(
                            "No installed pack manifest for \"$packId\" — " +
                                "the $kind \"$name\" can't be checked and the " +
                                "view will be unavailable until that pack is installed",
                            i,
                            Severity.Warning,
                        ))
                        !present -> add(Problem(
                            "Pack \"$packId\" (v${manifest.pack_version}) does " +
                                "not declare $kind \"$name\"",
                            i,
                            Severity.Warning,
                        ))
                    }
                }
                (view.source as? SourceRef.Pack)?.let { pack ->
                    checkPackRef(pack.packId, "source", pack.source,
                                 registry.byId(pack.packId)?.source(pack.source) != null)
                }
                view.actions.filterIsInstance<ActionDef.PackAction>().forEach { pa ->
                    checkPackRef(pa.packId, "action", pa.action,
                                 registry.byId(pa.packId)?.action(pa.action) != null)
                }
            }
            view.colTypes.filterIsInstance<ColType.Enum>().forEach { enum ->
                if (enum.options.none { it.isNotBlank() })
                    add(Problem("An enum needs at least one non-blank option", i))
                else if (enum.options.any { it.isBlank() })
                    add(Problem("An enum option is blank", i, Severity.Warning))
            }
            view.colTypes.filterIsInstance<ColType.Ref>().forEach { ref ->
                val target = spec.views.find { it.name == ref.targetView }
                if (target == null)
                    add(Problem(
                        "Reference target \"${ref.targetView}\" is not a live view",
                        i,
                    ))
                else if (target.kind !in setOf(ViewKind.RECORDS, ViewKind.NOTES))
                    add(Problem(
                        "Reference target \"${ref.targetView}\" must be a records or notes view",
                        i,
                    ))
                else if (target.kind == ViewKind.RECORDS &&
                    target.schema.none { it.prop.equals("ID", ignoreCase = true) })
                    add(Problem(
                        "Reference target \"${ref.targetView}\" needs an ID field",
                        i,
                    ))
                else if (ref.displayField != null && target.schema.none {
                        it.prop.equals(ref.displayField, ignoreCase = true)
                    })
                    add(Problem(
                        "Reference display field \"${ref.displayField}\" is not in target \"${ref.targetView}\"",
                        i,
                    ))
            }
            view.reminder?.let { reminder ->
                if (!isRecords)
                    add(Problem("Date reminders require a record-like view", i))
                if (view.schema.none {
                        it.prop.equals(reminder.dateField, ignoreCase = true)
                    })
                    add(Problem(
                        "Reminder date field \"${reminder.dateField}\" is not in this view's schema",
                        i,
                    ))
                if (reminder.relativeDays !in -3650..3650)
                    add(Problem("Reminder offset must be within ±3650 days", i))
                if (view.kind != ViewKind.NOTES && view.schema.none {
                        it.prop.equals("ID", ignoreCase = true)
                    })
                    add(Problem("Date reminders require an ID field for stable identity", i))
            }
            if (view.kind == ViewKind.DASHBOARD) {
                if (view.metrics.isEmpty())
                    add(Problem("A dashboard needs at least one metric", i))
                view.metrics.forEach { metric ->
                    when (metric.operation) {
                        AggregateOp.COUNT -> if (metric.field != null)
                            add(Problem("Count metric does not take a field", i))
                        AggregateOp.SUM, AggregateOp.AVG -> {
                            val field = metric.field
                            if (field.isNullOrBlank() || view.schema.none {
                                    it.prop.equals(field, ignoreCase = true)
                                })
                                add(Problem(
                                    "${metric.operation.name.lowercase()} metric field is not in this view's schema",
                                    i,
                                ))
                        }
                    }
                }
            }
            if (view.kind == ViewKind.GANTT) {
                listOf("TODO", "SCHEDULED", "DEADLINE").forEach { required ->
                    if (view.schema.none { it.prop.equals(required, ignoreCase = true) })
                        add(Problem("Gantt view needs a $required field", i))
                }
            }

            val todoKeywords = if (spec.todoSequence.isEmpty()) {
                setOf("TODO", "DONE")
            } else {
                spec.todoSequence.map { it.keyword }.toSet()
            }
            view.actions.filterIsInstance<ActionDef.SetTodo>().forEach { action ->
                if (action.keyword.isBlank())
                    add(Problem("A TODO action needs a keyword", i))
                else if (action.keyword !in todoKeywords)
                    add(Problem(
                        "Action TODO keyword \"${action.keyword}\" is not in the app's TODO sequence",
                        i,
                        Severity.Warning,
                    ))
            }
            
            // FIXME (T1.5): When `when_offline` is added to `ActionDef`, validate it here:
            // view.actions.forEach { action ->
            //     if (action.whenOffline != null && action.whenOffline !in ContractManifest.offlinePolicies) {
            //         add(Problem("Action offline policy \"${action.whenOffline}\" is invalid (must be one of ${ContractManifest.offlinePolicies})", i))
            //     }
            // }

            // Placement: :GROUP: wins over :NAV:, so a grouped drawer view is
            // a contradiction — the group is its bottom destination.
            if (view.group != null && view.nav == ViewNav.DRAWER)
                add(Problem(
                    "A grouped view ignores :NAV: drawer — it belongs to its group's destination",
                    i,
                    Severity.Warning,
                ))
        }
        // A tabulated group with a single member is just a tab with a
        // redundant tab row.
        spec.views.mapNotNull { it.group }
            .groupBy { it }
            .filter { it.value.size == 1 }
            .keys.forEach {
                add(Problem(
                    "Group \"$it\" has only one view — a group needs two or more to be worth a tab row",
                    severity = Severity.Warning,
                ))
            }
    }

    // ─── Schema Inference ────────────────────────────────────────────────

    /**
     * Infer a schema from org file content by scanning headings and
     * property drawers. Uses [SchemaField.ORG_BUILTINS] for known
     * properties; falls back to heuristic type detection for custom ones.
     */
    fun inferSchemaFromOrgContent(content: String): Pair<List<SchemaField>, List<ColType>> {
        val lines = content.lines()
        val propNames = mutableSetOf<String>()
        val propValues = mutableMapOf<String, MutableList<String>>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("* ")) {
                // Check for TODO keyword in heading
                val headingText = line.removePrefix("* ").trim()
                val firstWord = headingText.split(" ").firstOrNull() ?: ""
                if (firstWord.uppercase() in listOf("TODO","NEXT","DOING","WAITING","DONE","CANCELLED")) {
                    propNames.add("TODO")
                }

                var j = i + 1
                while (j < lines.size && lines[j].isBlank()) j++
                if (j < lines.size && lines[j].trim().equals(":PROPERTIES:", ignoreCase = true)) {
                    j++
                    while (j < lines.size && !lines[j].trim().equals(":END:", ignoreCase = true)) {
                        val propLine = lines[j].trim()
                        val match = Regex("""^:([A-Za-z_][A-Za-z_0-9-]*):\s*(.*)$""").find(propLine)
                        if (match != null) {
                            val name = match.groupValues[1].let {
                                if (it.uppercase() in SchemaField.SPECIAL) it.uppercase() else it
                            }
                            val value = match.groupValues[2].trim()
                            propNames.add(name)
                            if (value.isNotEmpty()) {
                                propValues.getOrPut(name) { mutableListOf() }.add(value)
                            }
                        }
                        j++
                    }
                }

                // Check for SCHEDULED/DEADLINE in planning line
                var k = i + 1
                while (k < lines.size && lines[k].isBlank()) k++
                if (k < lines.size) {
                    val planLine = lines[k].trim()
                    if (planLine.contains("SCHEDULED:")) propNames.add("SCHEDULED")
                    if (planLine.contains("DEADLINE:")) propNames.add("DEADLINE")
                }
            }
            i++
        }

        val schema = mutableListOf(SchemaField("ITEM", "Name"))
        val types = mutableListOf<ColType>(ColType.Text)

        for (name in propNames) {
            if (name == "ITEM") continue

            // Use ORG_BUILTINS for known properties
            val builtin = SchemaField.ORG_BUILTINS[name.uppercase()]
            if (builtin != null) {
                schema.add(SchemaField(name.uppercase(),
                    name.lowercase().replaceFirstChar { it.uppercase() }))
                types.add(builtin.defaultType)
                continue
            }

            // Heuristic for custom properties
            schema.add(SchemaField(name,
                name.lowercase().replaceFirstChar { it.uppercase() }))

            val values = propValues[name] ?: emptyList()
            if (values.isEmpty()) {
                types.add(ColType.Text)
                continue
            }

            val allNumbers = values.all { it.toDoubleOrNull() != null }
            val allDates = values.all { it.matches(Regex("""^[<\[]\d{4}-\d{2}-\d{2}.*[>\]].*""")) }
            val allCheckboxes = values.all { it == "[ ]" || it == "[X]" || it == "[x]" }

            when {
                allNumbers -> types.add(ColType.Number)
                allDates -> types.add(ColType.Date)
                allCheckboxes -> types.add(ColType.Checkbox)
                else -> types.add(ColType.Text)
            }
        }

        return schema to types
    }

    /** Helper for views with records-like kinds. */
    fun isRecordsType(kind: ViewKind): Boolean =
        kind in listOf(ViewKind.RECORDS, ViewKind.NOTES, ViewKind.BOARD, ViewKind.CALENDAR, ViewKind.GALLERY, ViewKind.TREE, ViewKind.DASHBOARD, ViewKind.GANTT, ViewKind.UNKNOWN)
}
