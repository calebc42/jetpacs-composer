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
        assertTrue(ModelOps.validate(clash).any { "slugify" in it.message })

        val external = ModelOps.updateView(base, 0) {
            it.copy(source = SourceRef("/sdcard/x.org"), columns = emptyList())
        }
        assertTrue(ModelOps.validate(external).any { "COLUMNS" in it.message })
    }
}
