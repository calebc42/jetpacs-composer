// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ActionDef
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.ViewSpec
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.org.OrgCodec
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The M1 acceptance flow, driven through the same operations the UI
 * calls: build a Pantry-like app from nothing, save, reopen, export.
 */
class EditorSessionTest {

    @Test
    fun undoRedoTracksDiscreteEditsAndClearsRedoOnBranch() {
        val session = EditorSession.fromSpec(AppSpec(id = "history"))
        session.update { it.copy(label = "First") }
        session.update { it.copy(icon = "apps") }

        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertTrue(session.undo())
        assertEquals(null, session.spec.icon)
        assertEquals("First", session.spec.label)
        assertTrue(session.canRedo)

        session.update { it.copy(label = "Branched") }
        assertFalse(session.canRedo)
        assertFalse(session.redo())
    }

    @Test
    fun typingWithOneCoalesceKeyUndoesAsOneEdit() {
        val session = EditorSession.fromSpec(AppSpec(id = "typing"))
        session.update("app.label") { it.copy(label = "A") }
        session.update("app.label") { it.copy(label = "AB") }
        session.update("app.label") { it.copy(label = "ABC") }

        assertEquals("ABC", session.spec.label)
        assertTrue(session.undo())
        assertNull(session.spec.label)
        assertFalse(session.canUndo)
        assertTrue(session.redo())
        assertEquals("ABC", session.spec.label)
    }

    @Test
    fun undoRecomputesDirtyAgainstTheLastSavedSpec() {
        val dir = createTempDirectory("composer-history-dirty").toFile()
        val session = EditorSession.fromSpec(AppSpec(id = "dirty"))
        assertNull(session.save(File(dir, "dirty.org")))
        assertFalse(session.dirty)

        session.update("app.label") { it.copy(label = "Changed") }
        assertTrue(session.dirty)
        assertTrue(session.undo())
        assertFalse(session.dirty)
        assertTrue(session.redo())
        assertTrue(session.dirty)
    }

    @Test
    fun savingEndsTheActiveTypingGroup() {
        val dir = createTempDirectory("composer-history-save-boundary").toFile()
        val session = EditorSession.fromSpec(AppSpec(id = "save-boundary"))
        session.update("app.label") { it.copy(label = "A") }
        assertNull(session.save(File(dir, "save-boundary.org")))

        session.update("app.label") { it.copy(label = "AB") }
        assertTrue(session.undo())
        assertEquals("A", session.spec.label)
        assertFalse(session.dirty)
    }

    @Test
    fun historyIsBounded() {
        val session = EditorSession.fromSpec(AppSpec(id = "bounded"))
        repeat(105) { value ->
            session.update { it.copy(label = "Edit $value") }
        }
        var undoCount = 0
        while (session.undo()) undoCount++
        assertEquals(100, undoCount)
        assertEquals("Edit 4", session.spec.label)
    }

    @Test
    fun buildSaveReopenExport() {
        val dir = createTempDirectory("composer-test").toFile()
        val session = EditorSession.new("pantry2", "Pantry Two")

        session.update { ModelOps.addView(it, "Inventory", ViewKind.TABLE) }
        session.update { ModelOps.updateView(it, 0) { v ->
            var out = ModelOps.setColumnName(v, 0, "Item")
            out = ModelOps.addColumn(out, "Qty")
            out = ModelOps.setColumnType(out, 1, ColType.Number)
            out = ModelOps.addColumn(out, "Stock")
            out = ModelOps.setColumnType(out, 2, ColType.Enum(listOf("Low", "High")))
            out = ModelOps.addRow(out)
            out = ModelOps.setCell(out, 0, 0, "Rice")
            out = ModelOps.setCell(out, 0, 1, "2")
            ModelOps.setCell(out, 0, 2, "High")
        } }
        session.update { ModelOps.addView(it, "Shopping", ViewKind.CHECKLIST) }
        session.update { ModelOps.updateView(it, 1) { v ->
            ModelOps.addItem(v, "Olive oil")
        } }

        assertTrue(ModelOps.validate(session.spec).isEmpty(),
                   ModelOps.validate(session.spec).joinToString { it.message })

        val file = File(dir, "pantry2.org")
        assertNull(session.save(file))

        // The saved canonical document reparses to the identical model.
        val reopened = EditorSession.open(file).getOrThrow()
        assertEquals(session.spec, reopened.spec)

        val bundle = session.export().getOrThrow()
        assertEquals("jetpacs-app-pantry2.el", bundle.name)
        val text = bundle.readText(Charsets.UTF_8)
        assertTrue(text.startsWith(";;; jetpacs-app-pantry2.el"))
        assertTrue("(jetpacs-crud-install \"pantry2\" " in text)
        assertTrue(":COLTYPES: text number enum(Low,High)" in text)

        // And the document inside the bundle is the document on disk.
        assertTrue(OrgCodec.write(reopened.spec) in text)
    }

    @Test
    fun exportBlocksErrorsWithoutTouchingAnExistingBundle() {
        val dir = createTempDirectory("composer-export-gate").toFile()
        var spec = ModelOps.addView(AppSpec(id = "blocked"), "Rows", ViewKind.TABLE)
        spec = ModelOps.updateView(spec, 0) {
            ModelOps.setColumnType(it, 0, ColType.Ref("missing"))
        }
        val session = EditorSession.fromSpec(spec)
        val document = File(dir, "blocked.org")
        assertNull(session.save(document), "ordinary app.org saves remain available")

        val bundle = File(dir, "jetpacs-app-blocked.el")
        bundle.writeText("existing bundle", Charsets.UTF_8)
        val result = session.export()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().startsWith("Bundle export blocked"))
        assertEquals("existing bundle", bundle.readText(Charsets.UTF_8))
    }

    @Test
    fun warningsDoNotBlockExport() {
        val dir = createTempDirectory("composer-export-warning").toFile()
        val spec = AppSpec(
            id = "warnings",
            views = listOf(
                ViewSpec(
                    title = "Records",
                    kind = ViewKind.RECORDS,
                    schema = listOf(SchemaField("ITEM")),
                    // More field types than schema fields — a Warning,
                    // not an Error.
                    colTypes = listOf(ColType.Text, ColType.Text),
                    actions = listOf(ActionDef.Schedule()),
                ),
            ),
        )
        assertTrue(ModelOps.validate(spec).any {
            it.severity == ModelOps.Severity.Warning
        })

        val session = EditorSession.fromSpec(spec)
        assertNull(session.save(File(dir, "warnings.org")))
        assertTrue(session.export().isSuccess)
    }

    @Test
    fun exportAlsoChecksThatTheGeneratedDocumentParses() {
        val dir = createTempDirectory("composer-export-parse-gate").toFile()
        val spec = AppSpec(
            id = "parse-gate",
            views = listOf(
                ViewSpec(
                    title = "Records",
                    kind = ViewKind.RECORDS,
                    schema = listOf(SchemaField("ITEM")),
                    colTypes = listOf(ColType.Text),
                    actions = listOf(ActionDef.SetTags(listOf("bad)"))),
                ),
            ),
        )
        assertTrue(ModelOps.validate(spec).none {
            it.severity == ModelOps.Severity.Error
        })

        val session = EditorSession.fromSpec(spec)
        assertNull(session.save(File(dir, "parse-gate.org")))
        val result = session.export()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty()
            .contains("generated app.org is invalid"))
        assertTrue(!File(dir, "jetpacs-app-parse-gate.el").exists())
    }
}
