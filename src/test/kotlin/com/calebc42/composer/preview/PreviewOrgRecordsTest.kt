// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PreviewOrgRecordsTest {
    private val contacts = """
        #+TODO: LEAD ACTIVE | LOST

        File prose outside every record.

        * Contacts
        Scope prose is not a record.

        ** ACTIVE [#A] Ada Lovelace :work:vip:
        SCHEDULED: <2026-07-15 Wed> DEADLINE: <2026-07-20 Mon>
        :PROPERTIES:
        :ID: ada-1
        :Phone: 555-0100
        :END:
        Notes about Ada.

        A second paragraph.

        *** LEAD Analytical Engine
        :PROPERTIES:
        :ID: engine-1
        :END:
        Child prose.

        ** LEAD Grace Hopper
        :PROPERTIES:
        :ID: grace-1
        :Phone: 555-0199
        :END:

        * Unrelated
        ** ACTIVE Not a contact
    """.trimIndent()

    @Test
    fun scopedRecordsReadOnlyDirectChildrenAndOrgSpecials() {
        val result = PreviewOrgRecords.read(
            contacts,
            PreviewOrgReadOptions(headingScope = "Contacts"),
        )

        assertTrue(result.scopeFound)
        assertEquals(1, result.parentLevel)
        assertEquals(listOf("Ada Lovelace", "Grace Hopper"),
            result.records.map { it.title })

        val ada = result.nodes.first()
        assertEquals("ada-1", ada.record.id)
        assertEquals("ada-1", ada.explicitId)
        assertEquals(2, ada.sourceLevel)
        assertEquals(0, ada.treeDepth)
        assertNull(ada.parentIndex)
        assertEquals(listOf("work", "vip"), ada.tags)
        assertEquals("Ada Lovelace", ada.record.values["ITEM"])
        assertEquals("ACTIVE", ada.record.values["TODO"])
        assertEquals("A", ada.record.values["PRIORITY"])
        assertEquals("work vip", ada.record.values["TAGS"])
        assertEquals("<2026-07-15 Wed>", ada.record.values["SCHEDULED"])
        assertEquals("<2026-07-20 Mon>", ada.record.values["DEADLINE"])
        assertEquals("555-0100", ada.record.values["Phone"])
        assertEquals("Notes about Ada.\n\nA second paragraph.", ada.record.body)

        assertEquals(PreviewProvenance.Inline, result.asInlineDataset().provenance)
        assertEquals(result.records, result.asInlineDataset().records)
    }

    @Test
    fun descendantTraversalRetainsRelativeDepthAndReturnedParent() {
        val result = PreviewOrgRecords.read(
            contacts,
            PreviewOrgReadOptions(
                headingScope = "* Contacts",
                traversal = PreviewOrgTraversal.DESCENDANTS,
            ),
        )

        assertEquals(
            listOf("Ada Lovelace", "Analytical Engine", "Grace Hopper"),
            result.records.map { it.title },
        )
        assertEquals(listOf(0, 1, 0), result.nodes.map { it.treeDepth })
        assertEquals(listOf(null, 0, null), result.nodes.map { it.parentIndex })
        assertEquals("Child prose.", result.records[1].body)
    }

    @Test
    fun noScopeUsesLevelOneAndInlineBodiesCanDeclareTheirParentLevel() {
        val wholeFile = PreviewOrgRecords.read(contacts)
        assertEquals(listOf("Contacts", "Unrelated"), wholeFile.records.map { it.title })

        val inlineBody = """
            ** TODO First
            :PROPERTIES:
            :ID: first
            :END:
            ** DONE Second
        """.trimIndent()
        val inline = PreviewOrgRecords.read(
            inlineBody,
            PreviewOrgReadOptions(unscopedParentLevel = 1),
        )
        assertEquals(listOf("First", "Second"), inline.records.map { it.title })
        assertEquals(listOf("TODO", "DONE"), inline.records.map { it.values["TODO"] })
    }

    @Test
    fun suppliedTodoVocabularySupportsDetachedInlineBodies() {
        val inline = PreviewOrgRecords.read(
            "** OPEN First\n** SHUT Second\n",
            PreviewOrgReadOptions(
                unscopedParentLevel = 1,
                todoKeywords = setOf("OPEN", "SHUT"),
            ),
        )
        assertEquals(listOf("First", "Second"), inline.records.map { it.title })
        assertEquals(listOf("OPEN", "SHUT"), inline.records.map { it.values["TODO"] })
    }

    @Test
    fun missingScopeAndMalformedMetadataDegradeWithoutThrowing() {
        val missing = PreviewOrgRecords.read(
            contacts,
            PreviewOrgReadOptions(headingScope = "Missing"),
        )
        assertFalse(missing.scopeFound)
        assertTrue(missing.records.isEmpty())

        val malformed = PreviewOrgRecords.read(
            """
                * TODO Still readable :tag:
                DEADLINE: [2026-07-31 Fri]
                :PROPERTIES:
                not a property
            """.trimIndent(),
        )
        assertEquals("Still readable", malformed.records.single().title)
        assertEquals("[2026-07-31 Fri]", malformed.records.single().values["DEADLINE"])
        assertEquals("preview-org-line-1", malformed.records.single().id)
    }
}
