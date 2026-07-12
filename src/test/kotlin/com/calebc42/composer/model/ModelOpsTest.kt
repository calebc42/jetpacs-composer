// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelOpsTest {

    private val base = ModelOps.addView(
        AppSpec(id = "t", views = emptyList()), "Stuff", ViewKind.TABLE)

    private fun table(spec: AppSpec) = ModelOps.firstTable(spec.views[0])!!

    @Test
    fun addViewSeedsUsableDefaults() {
        val view = base.views.single()
        assertEquals("stuff", view.name)
        assertEquals(listOf("Name"), table(base).header)
        assertEquals(listOf(ColType.Text), view.colTypes)
    }

    @Test
    fun addViewKeepsSlugsUnique() {
        val spec = ModelOps.addView(base, "Stuff", ViewKind.TABLE)
        assertEquals(listOf("stuff", "stuff-2"), spec.views.map { it.name })
    }

    @Test
    fun specializedViewsSeedTheFieldsTheirRuntimeDefaultsUse() {
        val cases = listOf(
            ViewKind.BOARD to "TODO",
            ViewKind.CALENDAR to "SCHEDULED",
            ViewKind.GALLERY to "IMAGE",
        )
        cases.forEach { (kind, expectedField) ->
            val spec = ModelOps.addView(AppSpec(id = "seed"), "View", kind)
            assertTrue(expectedField in spec.views.single().schema.map { it.prop })
            assertTrue(ModelOps.validate(spec).none {
                it.severity == ModelOps.Severity.Error
            })
        }
    }

    @Test
    fun columnOpsKeepRowsAndTypesAligned() {
        var spec = base
        spec = ModelOps.updateView(spec, 0) { ModelOps.addRow(it) }
        spec = ModelOps.updateView(spec, 0) { ModelOps.addColumn(it, "Qty") }
        spec = ModelOps.updateView(spec, 0) {
            ModelOps.setColumnType(it, 1, ColType.Number)
        }
        assertEquals(listOf("Name", "Qty"), table(spec).header)
        assertEquals(listOf(listOf("", "")), table(spec).rows)
        assertEquals(listOf(ColType.Text, ColType.Number), spec.views[0].colTypes)

        spec = ModelOps.updateView(spec, 0) { ModelOps.removeColumn(it, 0) }
        assertEquals(listOf("Qty"), table(spec).header)
        assertEquals(listOf(listOf("")), table(spec).rows)
        assertEquals(listOf(ColType.Number), spec.views[0].colTypes)
    }

    @Test
    fun addRowDefaultsCheckboxColumns() {
        var spec = ModelOps.updateView(base, 0) { ModelOps.addColumn(it, "Done") }
        spec = ModelOps.updateView(spec, 0) {
            ModelOps.setColumnType(it, 1, ColType.Checkbox)
        }
        spec = ModelOps.updateView(spec, 0) { ModelOps.addRow(it) }
        assertEquals(listOf("", "[ ]"), table(spec).rows.single())
    }

    @Test
    fun moveAndRemoveViews() {
        var spec = ModelOps.addView(base, "Second", ViewKind.CHECKLIST)
        spec = ModelOps.moveView(spec, 1, -1)
        assertEquals(listOf("Second", "Stuff"), spec.views.map { it.title })
        assertEquals(spec, ModelOps.moveView(spec, 0, -1)) // clamped
        spec = ModelOps.removeView(spec, 0)
        assertEquals(listOf("Stuff"), spec.views.map { it.title })
    }

    @Test
    fun checklistOps() {
        var spec = ModelOps.addView(
            AppSpec(id = "t"), "List", ViewKind.CHECKLIST)
        spec = ModelOps.updateView(spec, 0) { ModelOps.addItem(it, "Milk") }
        spec = ModelOps.updateView(spec, 0) {
            ModelOps.setItem(it, 0, ChecklistItem("X", "Milk"))
        }
        assertEquals(listOf(ChecklistItem("X", "Milk")),
                     ModelOps.firstChecklist(spec.views[0])!!.items)
        spec = ModelOps.updateView(spec, 0) { ModelOps.removeItem(it, 0) }
        assertTrue(ModelOps.firstChecklist(spec.views[0])!!.items.isEmpty())
    }

    @Test
    fun validateFlagsTheFormatsRemainingFootguns() {
        // addView uniquifies, so force a clash directly.
        val clash = base.copy(views = base.views + base.views[0].copy(title = "Stuff!"))
        assertTrue(ModelOps.validate(clash).any {
            "slugify" in it.message && it.severity == ModelOps.Severity.Error
        })

        val external = ModelOps.updateView(base, 0) {
            it.copy(source = SourceRef.File("/sdcard/x.org"), columns = emptyList())
        }
        assertTrue(ModelOps.validate(external).any {
            "COLUMNS" in it.message && it.severity == ModelOps.Severity.Warning
        })

        val emptyEnum = ModelOps.updateView(base, 0) {
            ModelOps.setColumnType(it, 0, ColType.Enum(emptyList()))
        }
        assertTrue(ModelOps.validate(emptyEnum).any {
            "enum needs" in it.message && it.severity == ModelOps.Severity.Error
        })
    }

    @Test
    fun validateBlocksRefsAndChecksReferencedFields() {
        val target = ViewSpec(
            title = "Customers",
            kind = ViewKind.RECORDS,
            schema = listOf(SchemaField("ITEM")),
            colTypes = listOf(ColType.Text),
        )
        val referrer = base.views.single().copy(
            colTypes = listOf(ColType.Ref("customers")),
        )
        val refProblems = ModelOps.validate(
            AppSpec(id = "refs", views = listOf(referrer, target)))
        assertTrue(refProblems.any {
            "FORMAT-2" in it.message && it.severity == ModelOps.Severity.Error
        })
        assertTrue(refProblems.none { "not a live view" in it.message })

        val missingTarget = referrer.copy(colTypes = listOf(ColType.Ref("missing")))
        assertTrue(ModelOps.validate(AppSpec(id = "refs", views = listOf(missingTarget)))
            .any { "not a live view" in it.message })

        val badFields = ViewSpec(
            title = "Board",
            kind = ViewKind.BOARD,
            schema = listOf(SchemaField("ITEM")),
            colTypes = listOf(ColType.Text),
            groupBy = "Status",
            dateField = "When",
            imageField = "Photo",
        )
        val messages = ModelOps.validate(AppSpec(id = "fields", views = listOf(badFields)))
            .map { it.message }
        assertTrue(messages.any { it.startsWith("Group-by field") })
        assertTrue(messages.any { it.startsWith("Date field") })
        assertTrue(messages.any { it.startsWith("Image field") })
    }

    @Test
    fun validateActionTodoKeywordsAgainstCustomOrDefaultSequence() {
        fun problemsFor(spec: AppSpec, keyword: String) = ModelOps.validate(
            spec.copy(views = listOf(
                ViewSpec(
                    title = "Records",
                    kind = ViewKind.RECORDS,
                    schema = listOf(SchemaField("ITEM")),
                    colTypes = listOf(ColType.Text),
                    actions = listOf(ActionDef.SetTodo(keyword)),
                ),
            )))

        val defaults = AppSpec(id = "actions")
        assertTrue(problemsFor(defaults, "DONE")
            .none { it.message.startsWith("Action TODO keyword") })
        assertTrue(problemsFor(defaults, "DOING").any {
            it.message.startsWith("Action TODO keyword") &&
                it.severity == ModelOps.Severity.Warning
        })

        val custom = defaults.copy(
            todoSequence = listOf(
                TodoKeyword("DOING", isDone = false),
                TodoKeyword("DONE", isDone = true),
            ))
        assertTrue(problemsFor(custom, "DOING")
            .none { it.message.startsWith("Action TODO keyword") })
    }

    @Test
    fun validateCatchesParserRejectsAndRuntimeFieldDefaults() {
        assertTrue(ModelOps.validate(AppSpec(id = "empty")).any {
            it.message == "An app needs at least one view" &&
                it.severity == ModelOps.Severity.Error
        })

        val notes = ModelOps.addView(AppSpec(id = "notes"), "Notes", ViewKind.NOTES)
        assertTrue(ModelOps.validate(notes).any {
            "external source" in it.message && it.severity == ModelOps.Severity.Error
        })

        val defaults = listOf(
            ViewSpec(
                title = "Board",
                kind = ViewKind.BOARD,
                schema = listOf(SchemaField("ITEM")),
                colTypes = listOf(ColType.Text),
            ) to "Group-by field",
            ViewSpec(
                title = "Calendar",
                kind = ViewKind.CALENDAR,
                schema = listOf(SchemaField("ITEM")),
                colTypes = listOf(ColType.Text),
            ) to "Date field",
            ViewSpec(
                title = "Gallery",
                kind = ViewKind.GALLERY,
                schema = listOf(SchemaField("ITEM")),
                colTypes = listOf(ColType.Text),
            ) to "Image field",
        )
        defaults.forEach { (view, messageStart) ->
            assertTrue(ModelOps.validate(AppSpec(id = "defaults", views = listOf(view)))
                .any { it.message.startsWith(messageStart) })
        }
    }
}
