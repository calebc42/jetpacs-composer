// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FilterQueryTest {

    @Test
    fun parsesTokenFiltersIntoGuidedClauses() {
        val result = assertIs<FilterQuery.ParseResult.Guided>(
            FilterQuery.parse("todo:NEXT,DONE tags:work priority:A", ViewKind.RECORDS))
        assertEquals(
            listOf(
                FilterQuery.Clause(FilterQuery.Term.Todo, values = listOf("NEXT", "DONE")),
                FilterQuery.Clause(FilterQuery.Term.Tags, values = listOf("work")),
                FilterQuery.Clause(FilterQuery.Term.Priority, values = listOf("A")),
            ),
            result.clauses,
        )
    }

    @Test
    fun parsesAndSerializesSupportedSexp() {
        val text = """(and (todo "NEXT") (property "Tier" "Gold") (deadline))"""
        val parsed = assertIs<FilterQuery.ParseResult.Guided>(
            FilterQuery.parse(text, ViewKind.RECORDS))
        assertEquals(text, FilterQuery.serialize(parsed.clauses))
    }

    @Test
    fun preservesNestedAndOrgQlOnlyExpressionsInRawMode() {
        assertIs<FilterQuery.ParseResult.Raw>(
            FilterQuery.parse("(or (todo \"NEXT\") (tags \"work\"))", ViewKind.RECORDS))
        assertIs<FilterQuery.ParseResult.Raw>(
            FilterQuery.parse("(clocked :on today)", ViewKind.RECORDS))
        assertIs<FilterQuery.ParseResult.Raw>(
            FilterQuery.parse("(priority > \"A\")", ViewKind.RECORDS))
        assertIs<FilterQuery.ParseResult.Raw>(
            FilterQuery.parse("renewal gold", ViewKind.RECORDS))
    }

    @Test
    fun notesShareTheFullRecordSubset() {
        // The note index matcher now covers the same subset as records, so
        // notes accept every term (and level ranges), not a reduced set.
        assertTrue(FilterQuery.Term.Priority in FilterQuery.allowedTerms(ViewKind.NOTES))
        assertIs<FilterQuery.ParseResult.Guided>(
            FilterQuery.parse("(priority \"A\")", ViewKind.NOTES))
        assertIs<FilterQuery.ParseResult.Guided>(
            FilterQuery.parse("(property \"Tier\" \"Gold\")", ViewKind.NOTES))
        assertIs<FilterQuery.ParseResult.Guided>(
            FilterQuery.parse("(level 1 3)", ViewKind.NOTES))
        // Nested boolean expressions still round-trip in raw mode, both kinds.
        assertIs<FilterQuery.ParseResult.Raw>(
            FilterQuery.parse("(not (priority \"A\"))", ViewKind.NOTES))
    }

    @Test
    fun requiredDependsReflectsKindsAndFilters() {
        val app = AppSpec(id = "a", views = listOf(
            ViewSpec(title = "T", kind = ViewKind.TABLE),
            ViewSpec(title = "R", kind = ViewKind.RECORDS, filter = "(todo \"NEXT\")"),
        ))
        // Any real view needs vulpea; a guided FILTER does not pull in org-ql.
        assertEquals(listOf("vulpea"), app.requiredDepends())
        // A raw/org-ql-only FILTER adds org-ql.
        val withOrgQl = app.copy(views = app.views + ViewSpec(
            title = "X", kind = ViewKind.RECORDS, filter = "(clocked :on today)"))
        assertEquals(listOf("vulpea", "org-ql"), withOrgQl.requiredDepends())
        // An UNKNOWN-only app needs nothing.
        assertTrue(AppSpec(id = "u", views = listOf(
            ViewSpec(title = "U", kind = ViewKind.UNKNOWN))).requiredDepends().isEmpty())
    }

    @Test
    fun malformedExpressionsAreInvalid() {
        assertIs<FilterQuery.ParseResult.Invalid>(
            FilterQuery.parse("(and (todo \"NEXT\")", ViewKind.RECORDS))
        assertIs<FilterQuery.ParseResult.Invalid>(
            FilterQuery.parse("todo:", ViewKind.RECORDS))
    }

    @Test
    fun validatesGuidedClauseRequirements() {
        assertTrue(FilterQuery.validate(listOf(
            FilterQuery.Clause(FilterQuery.Term.Property)), ViewKind.RECORDS).isNotEmpty())
        assertTrue(FilterQuery.validate(listOf(
            FilterQuery.Clause(FilterQuery.Term.Level, values = listOf("one"))),
            ViewKind.RECORDS).isNotEmpty())
        val range = listOf(
            FilterQuery.Clause(FilterQuery.Term.Level, values = listOf("1", "3")))
        assertTrue(FilterQuery.validate(range, ViewKind.RECORDS).isEmpty())
        // Level ranges are valid in notes now too (shared subset).
        assertTrue(FilterQuery.validate(range, ViewKind.NOTES).isEmpty())
    }

    @Test
    fun stringArgumentsRoundTripEscapes() {
        val clause = FilterQuery.Clause(
            FilterQuery.Term.Property,
            subject = "Owner",
            values = listOf("A \\\"quoted\\\" value"),
        )
        val serialized = FilterQuery.serialize(listOf(clause))
        val reparsed = assertIs<FilterQuery.ParseResult.Guided>(
            FilterQuery.parse(serialized, ViewKind.RECORDS))
        assertEquals(listOf(clause), reparsed.clauses)
    }
}
