// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PreviewPresentationTest {
    @Test
    fun `formats typed values without changing raw data`() {
        assertEquals("2026-07-12", PreviewPresentation.value(ColType.Date, "<2026-07-12 Sun>").text)
        assertEquals(true, PreviewPresentation.value(ColType.Checkbox, "[X]").checked)
        assertEquals(false, PreviewPresentation.value(ColType.Checkbox, "[ ]").checked)
        assertEquals("42.5", PreviewPresentation.value(ColType.Number, "42.5").text)
        assertEquals("\u2014", PreviewPresentation.value(ColType.Text, "").text)
    }

    @Test
    fun `resolves reference labels and target records`() {
        val spec = AppSpec(
            id = "refs",
            views = listOf(
                ViewSpec("Projects", kind = ViewKind.RECORDS, schema = listOf(SchemaField("ITEM"))),
                ViewSpec(
                    "Tasks",
                    kind = ViewKind.RECORDS,
                    schema = listOf(SchemaField("PROJECT")),
                    colTypes = listOf(ColType.Ref("projects", "CODE")),
                ),
            ),
        )
        val app = PreviewProjection.project(spec)
        val target = PreviewRecord("project-1", "Alpha", mapOf("CODE" to "ALP"))
        val datasets = mapOf("projects" to PreviewDataset(PreviewProvenance.Sample, listOf(target)))

        val resolved = PreviewPresentation.resolveReference(
            app, datasets, ColType.Ref("projects", "CODE"), "project-1",
        )

        assertTrue(resolved.resolved)
        assertEquals("ALP", resolved.label)
        assertSame(target, resolved.record)
    }

    @Test
    fun `unresolved references remain visible and inert`() {
        val app = PreviewProjection.project(AppSpec(id = "empty"))
        val resolved = PreviewPresentation.resolveReference(
            app, emptyMap(), ColType.Ref("missing"), "stable-id",
        )

        assertFalse(resolved.resolved)
        assertEquals("stable-id", resolved.label)
    }
}
