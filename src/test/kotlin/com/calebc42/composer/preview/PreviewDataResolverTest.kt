// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ChecklistItem
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.TodoKeyword
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PreviewDataResolverTest {
    @Test
    fun autoUsesExactInlineTableAndChecklistData() {
        val table = ViewSpec(
            title = "Inventory",
            body = listOf(BodyElement.Table(
                header = listOf("Item", "Qty"),
                rows = listOf(listOf("Rice", "2")),
            )),
        )
        val checklist = ViewSpec(
            title = "Shopping",
            kind = ViewKind.CHECKLIST,
            body = listOf(BodyElement.Checklist(listOf(ChecklistItem("X", "Coffee")))),
        )
        val spec = AppSpec("inline", views = listOf(table, checklist))

        val tableData = PreviewDataResolver.resolve(spec, 0)
        assertIs<PreviewProvenance.Inline>(tableData.provenance)
        assertEquals(mapOf("Item" to "Rice", "Qty" to "2"), tableData.records.single().values)

        val checklistData = PreviewDataResolver.resolve(spec, 1)
        assertIs<PreviewProvenance.Inline>(checklistData.provenance)
        assertEquals("[X]", checklistData.records.single().values["CHECKED"])
    }

    @Test
    fun autoProjectsInlineRecordsAndTreeDepthSource() {
        val raw = BodyElement.Raw("""
            ** TODO Parent
            :PROPERTIES:
            :ID: p1
            :END:
            Parent prose.
            *** Child
            :PROPERTIES:
            :ID: c1
            :END:
        """.trimIndent())
        val records = ViewSpec(
            title = "Records", kind = ViewKind.RECORDS,
            schema = listOf(SchemaField("ITEM"), SchemaField("TODO")),
            body = listOf(raw),
        )
        val tree = records.copy(title = "Tree", kind = ViewKind.TREE)
        val spec = AppSpec(
            "org", todoSequence = listOf(TodoKeyword("TODO", false)),
            views = listOf(records, tree),
        )

        assertEquals(listOf("Parent"),
            PreviewDataResolver.resolve(spec, 0).records.map { it.title })
        assertEquals(listOf("Parent", "Child"),
            PreviewDataResolver.resolve(spec, 1).records.map { it.title })
        assertEquals("Parent prose.", PreviewDataResolver.resolve(spec, 0).records.single().body)
    }

    @Test
    fun externalSourcesAreSampledWithExplicitNoticeAndEmptyModeWins() {
        val view = ViewSpec(
            title = "Remote", kind = ViewKind.RECORDS,
            source = SourceRef.File("/sdcard/org/remote.org"),
            schema = listOf(SchemaField("ITEM")),
        )
        val spec = AppSpec("remote", views = listOf(view))

        val automatic = PreviewDataResolver.resolve(spec, 0)
        assertIs<PreviewProvenance.Sample>(automatic.provenance)
        assertTrue(automatic.records.isNotEmpty())
        assertEquals("sample-external-source", automatic.notices.single().code)

        val empty = PreviewDataResolver.resolve(spec, 0, PreviewDataMode.EMPTY)
        assertIs<PreviewProvenance.Empty>(empty.provenance)
        assertTrue(empty.records.isEmpty())
    }
}
