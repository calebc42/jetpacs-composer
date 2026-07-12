// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.org

import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.DateReminderRule
import com.calebc42.composer.model.SourceRef
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

    private data class ParserParityCase(
        val name: String,
        val expectation: String,
        val fixture: String,
    )

    private fun parserParityCases(): List<ParserParityCase> =
        fixture("parser-parity.manifest").lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val fields = line.split(Regex("\\s+"))
                require(fields.size == 3) { "bad parser parity manifest line: $line" }
                ParserParityCase(fields[0], fields[1], fields[2])
            }
            .toList()

    @Test
    fun sharedParserParityManifest() {
        for (case in parserParityCases()) {
            val result = runCatching { OrgCodec.parse(fixture(case.fixture)) }
            when (case.expectation) {
                "accept" -> assertTrue(
                    result.isSuccess,
                    "${case.name} should accept ${case.fixture}, got ${result.exceptionOrNull()}",
                )
                "reject" -> assertTrue(
                    result.exceptionOrNull() is OrgCodec.FormatException,
                    "${case.name} should reject ${case.fixture} with FormatException",
                )
                else -> error("unknown parser parity expectation ${case.expectation}")
            }
        }
    }

    @Test
    fun referenceDisplayFieldRoundTrips() {
        val text = """
            #+JETPACS_APP: refs
            #+JETPACS_APP_FORMAT: 2

            * Orders
            :PROPERTIES:
            :KIND: records
            :SCHEMA: %ITEM %CUSTOMER
            :COLTYPES: text ref(customers,NAME)
            :END:
        """.trimIndent()
        val spec = OrgCodec.parse(text)
        assertEquals(ColType.Ref("customers", "NAME"), spec.views.single().colTypes[1])
        assertEquals(spec, OrgCodec.parse(OrgCodec.write(spec)))
    }

    @Test
    fun dateReminderRuleRoundTrips() {
        val text = """
            #+JETPACS_APP: reminders
            #+JETPACS_APP_FORMAT: 2

            * Tasks
            :PROPERTIES:
            :KIND: records
            :SCHEMA: %ITEM %ID %DEADLINE
            :COLTYPES: text text date
            :ON: date-field
            :REL: -3d
            :DATEFIELD: DEADLINE
            :END:
        """.trimIndent()
        val spec = OrgCodec.parse(text)
        assertEquals(DateReminderRule("DEADLINE", -3), spec.views.single().reminder)
        assertEquals(spec, OrgCodec.parse(OrgCodec.write(spec)))
    }

    @Test
    fun malformedDateReminderRuleIsRejected() {
        val text = """
            #+JETPACS_APP: reminders
            #+JETPACS_APP_FORMAT: 2
            * Tasks
            :PROPERTIES:
            :KIND: records
            :SCHEMA: %ITEM
            :ON: date-field
            :REL: tomorrow
            :DATEFIELD: DEADLINE
            :END:
        """.trimIndent()
        assertFailsWith<OrgCodec.FormatException> { OrgCodec.parse(text) }
    }

    @Test
    fun quickCaptureInboxRoundTrips() {
        val text = """
            #+JETPACS_APP: capture
            #+JETPACS_APP_FORMAT: 2
            #+JETPACS_INBOX: inbox.org

            * Notes
            :PROPERTIES:
            :KIND: records
            :SCHEMA: %ITEM
            :END:
        """.trimIndent()
        val spec = OrgCodec.parse(text)
        assertEquals("inbox.org", spec.inbox)
        assertEquals(spec, OrgCodec.parse(OrgCodec.write(spec)))
    }

    @Test
    fun parserParityFixtureExercisesTheWholeAcceptedSurface() {
        val spec = OrgCodec.parse(fixture("parser-parity-all.org"))

        assertEquals("parser-parity", spec.id)
        assertEquals("Parser parity", spec.label)
        assertEquals("rule", spec.icon)
        assertEquals(7, spec.order)
        assertEquals(listOf("TODO", "NEXT", "DONE"),
                     spec.todoSequence.map { it.keyword })
        assertEquals(listOf("work", "home"), spec.tags)
        assertEquals(
            listOf(ViewKind.TABLE, ViewKind.CHECKLIST, ViewKind.RECORDS,
                   ViewKind.NOTES, ViewKind.BOARD, ViewKind.CALENDAR,
                   ViewKind.GALLERY),
            spec.views.map { it.kind },
        )
        val table = spec.views.first()
        assertEquals("table_chart", table.icon)
        assertEquals(10, table.order)
        assertEquals(listOf("Text", "Number", "Date", "Done", "Choice"),
                     table.columns)
        assertEquals(
            listOf(ColType.Text, ColType.Number, ColType.Date, ColType.Checkbox,
                   ColType.Enum(listOf("A", "B"))),
            table.colTypes,
        )
        val records = spec.views.first { it.kind == ViewKind.RECORDS }
        assertEquals(
            listOf("ITEM", "TODO", "DEADLINE", "SCHEDULED", "PRIORITY",
                   "TAGS", "EFFORT", "CATEGORY"),
            records.schema.map { it.prop },
        )
        assertEquals(
            listOf("todo(DONE)", "schedule", "deadline", "tags(work,home)",
                   "priority(A)", "refile(* Archive)", "archive(subtree)"),
            records.actions.map { it.toToken() },
        )
        assertEquals("""(todo "TODO")""", records.filter)
        assertEquals(SourceRef.Dir("vault"),
                     spec.views.first { it.kind == ViewKind.NOTES }.source)
        assertEquals("TODO", spec.views.first { it.kind == ViewKind.BOARD }.groupBy)
        assertEquals("DEADLINE",
                     spec.views.first { it.kind == ViewKind.CALENDAR }.dateField)
        assertEquals("Photo",
                     spec.views.first { it.kind == ViewKind.GALLERY }.imageField)
        assertEquals(spec, OrgCodec.parse(OrgCodec.write(spec)))
    }

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
        val source = view.source as SourceRef.File
        assertEquals("stock-backend.org", source.file)
        assertEquals("Stock", source.heading)
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
    fun parsesNotesViews() {
        val spec = OrgCodec.parse(fixture("contacts.org"))
        assertEquals("contacts", spec.id)
        assertEquals(2, spec.views.size)

        val people = spec.views[0]
        assertEquals(ViewKind.NOTES, people.kind)
        // A trailing-slash SOURCE is a note vault, not a file::*heading.
        assertEquals(SourceRef.Dir("vault"), people.source)
        assertEquals(listOf("ITEM", "Phone", "Tier"), people.schema.map { it.prop })
        assertEquals("Name", people.schema[0].label)
        assertEquals(ColType.Enum(listOf("Gold", "Silver", "Bronze")), people.colTypes[2])

        val team = spec.views[1]
        assertEquals(ViewKind.NOTES, team.kind)
        assertEquals(SourceRef.File("roster.org", "Team"), team.source)
    }

    @Test
    fun notesRoundTrip() {
        val once = OrgCodec.parse(fixture("contacts.org"))
        assertEquals(once, OrgCodec.parse(OrgCodec.write(once)))
    }

    @Test
    fun notesViewRequiresSchema() {
        assertFailsWith<OrgCodec.FormatException> {
            OrgCodec.parse(
                "#+JETPACS_APP: x\n\n* V\n:PROPERTIES:\n:KIND: notes\n:SOURCE: vault/\n:END:\n")
        }
    }

    @Test
    fun notesViewRequiresSource() {
        assertFailsWith<OrgCodec.FormatException> {
            OrgCodec.parse(
                "#+JETPACS_APP: x\n\n* V\n:PROPERTIES:\n:KIND: notes\n:SCHEMA: %ITEM\n:END:\n")
        }
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
    fun parsesParityFixture() {
        // Shared accept/reject fixture between JVM and Elisp.
        val spec = OrgCodec.parse(fixture("app-parity.org"))
        assertEquals("parity", spec.id)
        assertEquals(5, spec.views.size)

        val board = spec.views[0]
        assertEquals(ViewKind.BOARD, board.kind)
        assertEquals("TODO", board.groupBy)

        val calendar = spec.views[1]
        assertEquals(ViewKind.CALENDAR, calendar.kind)
        assertEquals("SCHEDULED", calendar.dateField)

        val gallery = spec.views[2]
        assertEquals(ViewKind.GALLERY, gallery.kind)
        assertEquals("IMAGE", gallery.imageField)

        val table = spec.views[3]
        assertEquals(ViewKind.TABLE, table.kind)

        val records = spec.views[4]
        assertEquals(ViewKind.RECORDS, records.kind)
    }

    @Test
    fun rejectsMalformed() {
        for (name in listOf("malformed-no-app.org", "malformed-dup.org")) {
            assertFailsWith<OrgCodec.FormatException>(name) {
                OrgCodec.parse(fixture(name))
            }
        }
    }

    @Test
    fun parsesUnknownTokens() {
        val spec = OrgCodec.parse(fixture("malformed-coltype.org"))
        val view = spec.views[0]
        assertTrue(view.colTypes.any { it is ColType.Unknown })
    }

    @Test
    fun roundTripsEveryValidFixture() {
        for (name in listOf("pantry.org", "case-variants.org",
                            "external-source.org", "unicode.org", "contacts.org")) {
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
        assertTrue("#+JETPACS_APP_FORMAT: 2\n" in text)
        assertTrue(":COLTYPES: text number date enum(Low,Mid,High) checkbox" in text)
        assertTrue("| Rice | 2   | 2026-09-01 | Mid   | [ ]     |" in text)
    }

    @Test
    fun formatTwoIsTheCleanCutoverVersion() {
        val body = "\n* Table\n\n| Name |\n|------+\n| one  |\n"
        assertEquals("current", OrgCodec.parse(
            "#+JETPACS_APP: current$body").id)
        assertFailsWith<OrgCodec.FormatException> {
            OrgCodec.parse(
                "#+JETPACS_APP: old\n#+JETPACS_APP_FORMAT: 1$body")
        }
    }
}
