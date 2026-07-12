// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.org.OrgCodec
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The M1 acceptance flow, driven through the same operations the UI
 * calls: build a Pantry-like app from nothing, save, reopen, export.
 */
class EditorSessionTest {

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
}
