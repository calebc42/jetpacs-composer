// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.org

import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ViewKind
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract tests over the SAME fixture corpus the ERT suite pins
 * (elisp/test/fixtures, via the fixtures.dir system property) — the two
 * parsers must accept and reject the same documents.
 */
class OrgCodecTest {

    private fun fixture(name: String): String =
        File(System.getProperty("fixtures.dir"), name).readText(Charsets.UTF_8)

    @Test
    fun parsesPantry() {
        val spec = OrgCodec.parse(fixture("pantry.org"))
        assertEquals("pantry", spec.id)
        assertEquals("Pantry", spec.label)
        assertEquals("kitchen", spec.icon)
        assertEquals(2, spec.views.size)

        val inv = spec.views[0]
        assertEquals("inventory", inv.name)
        assertEquals(ViewKind.TABLE, inv.kind)
        assertNull(inv.source)
        assertEquals(10, inv.order)
        assertEquals(
            listOf(ColType.Text, ColType.Number, ColType.Date,
                   ColType.Enum(listOf("Low", "Mid", "High")), ColType.Checkbox),
            inv.colTypes,
        )
        val table = inv.body.filterIsInstance<BodyElement.Table>().single()
        assertEquals(listOf("Item", "Qty", "Expires", "Stock", "Restock"), table.header)
        assertEquals(listOf("Rice", "2", "2026-09-01", "Mid", "[ ]"), table.rows[0])

        val shop = spec.views[1]
        assertEquals(ViewKind.CHECKLIST, shop.kind)
        assertEquals(20, shop.order)
        val items = shop.body.filterIsInstance<BodyElement.Checklist>().single().items
        assertEquals(listOf(" " to "Olive oil", "X" to "Coffee"),
                     items.map { it.state to it.text })
    }

    @Test
    fun parsesCaseVariants() {
        val spec = OrgCodec.parse(fixture("case-variants.org"))
        assertEquals("shelf", spec.id)
        assertEquals("inventory_2", spec.icon)
        assertEquals(42, spec.order)
        val view = spec.views.single()
        assertEquals(listOf(ColType.Text, ColType.Number), view.colTypes)
        assertEquals("category", view.icon)
        assertEquals(7, view.order)
    }

    @Test
    fun parsesExternalSource() {
        val spec = OrgCodec.parse(fixture("external-source.org"))
        val view = spec.views.single()
        assertEquals("stock-backend.org", view.source?.file)
        assertEquals("Stock", view.source?.heading)
        assertEquals(listOf("Part", "Count"), view.columns)
    }

    @Test
    fun parsesUnicode() {
        val spec = OrgCodec.parse(fixture("unicode.org"))
        assertEquals("食料品 🍙", spec.label)
        assertEquals(1, spec.views.size)
    }

    @Test
    fun parsesRecordsViews() {
        val spec = OrgCodec.parse(fixture("crm.org"))
        assertEquals("crm", spec.id)
        assertEquals(3, spec.views.size)

        val people = spec.views[0]
        assertEquals(com.calebc42.composer.model.ViewKind.RECORDS, people.kind)
        // %todo upcased to the special property; labels preserved.
        assertEquals(listOf("ITEM", "TODO", "Phone", "Tier", "DEADLINE"),
                     people.schema.map { it.prop })
        assertEquals("Status", people.schema[1].label)
        assertNull(people.filter)

        val gold = spec.views[1]
        assertEquals("""(property "Tier" "Gold")""", gold.filter)

        // Inline records (level-2 headings) pass through as raw body.
        val scratch = spec.views[2]
        assertTrue(scratch.body.filterIsInstance<BodyElement.Raw>()
                       .any { "First scratch record" in it.text })
    }

    @Test
    fun recordsRoundTrip() {
        val once = OrgCodec.parse(fixture("crm.org"))
        assertEquals(once, OrgCodec.parse(OrgCodec.write(once)))
    }

    @Test
    fun recordsViewRequiresSchema() {
        assertFailsWith<OrgCodec.FormatException> {
            OrgCodec.parse("#+JETPACS_APP: x\n\n* V\n:PROPERTIES:\n:KIND: records\n:END:\n")
        }
    }

    @Test
    fun rejectsMalformed() {
        for (name in listOf("malformed-no-app.org", "malformed-coltype.org",
                            "malformed-dup.org")) {
            assertFailsWith<OrgCodec.FormatException>(name) {
                OrgCodec.parse(fixture(name))
            }
        }
    }

    @Test
    fun roundTripsEveryValidFixture() {
        for (name in listOf("pantry.org", "case-variants.org",
                            "external-source.org", "unicode.org")) {
            val once = OrgCodec.parse(fixture(name))
            val twice = OrgCodec.parse(OrgCodec.write(once))
            assertEquals(once, twice, "round-trip drift in $name")
        }
    }

    @Test
    fun writerEmitsParsableCanonicalForm() {
        val spec = OrgCodec.parse(fixture("pantry.org"))
        val text = OrgCodec.write(spec)
        assertTrue(text.startsWith("#+JETPACS_APP: pantry\n"))
        assertTrue(":COLTYPES: text number date enum(Low,Mid,High) checkbox" in text)
        assertTrue("| Rice | 2   | 2026-09-01 | Mid   | [ ]     |" in text)
    }
}
