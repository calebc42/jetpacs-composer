// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.TodoKeyword
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PreviewSamplesTest {
    private val clock = Clock.fixed(Instant.parse("2026-07-12T12:00:00Z"), ZoneOffset.UTC)

    private val customers = ViewSpec(
        title = "Customers",
        kind = ViewKind.RECORDS,
        schema = listOf(
            SchemaField("ITEM", "Customer"),
            SchemaField("ID"),
        ),
    )

    private val orders = ViewSpec(
        title = "Orders",
        kind = ViewKind.RECORDS,
        schema = listOf(
            SchemaField("ITEM", "Order"),
            SchemaField("ID"),
            SchemaField("Amount"),
            SchemaField("Due"),
            SchemaField("Paid"),
            SchemaField("Tier"),
            SchemaField("TODO", "Status"),
            SchemaField("Customer"),
            SchemaField("Future"),
        ),
        colTypes = listOf(
            ColType.Text,
            ColType.Text,
            ColType.Number,
            ColType.Date,
            ColType.Checkbox,
            ColType.Enum(listOf("Basic", "Plus", "Premium")),
            ColType.Enum(listOf("ignored-default")),
            ColType.Ref("customers", "ITEM"),
            ColType.Unknown("future-type"),
        ),
    )

    private val spec = AppSpec(
        id = "demo",
        todoSequence = listOf(
            TodoKeyword("NEXT", false),
            TodoKeyword("WAITING", false),
            TodoKeyword("DONE", true),
        ),
        views = listOf(customers, orders),
    )

    @Test
    fun samplesAreDeterministicTypedAndHumanReadable() {
        val generator = PreviewSampleGenerator(clock)

        val first = generator.generate(spec)
        val second = generator.generate(spec)

        assertEquals(first, second)
        assertEquals("2026-07-12", first.generatedOn.toString())

        val records = first.recordsFor("orders")
        assertEquals(4, records.size)
        assertEquals("demo-orders-sample-01", records[0].id)
        assertEquals("Orders item 1", records[0].title)
        assertEquals(records[0].id, records[0].values["ID"])
        assertTrue(records.map { it.values.getValue("Amount").toInt() }.zipWithNext().all { (a, b) -> b > a })
        assertEquals(
            listOf("<2026-07-09 Thu>", "<2026-07-12 Sun>", "<2026-07-16 Thu>", "<2026-07-22 Wed>"),
            records.map { it.values["Due"] },
        )
        assertEquals(listOf("[ ]", "[X]", "[ ]", "[X]"), records.map { it.values["Paid"] })
        assertEquals(listOf("Basic", "Plus", "Premium", "Basic"), records.map { it.values["Tier"] })
        assertEquals(listOf("NEXT", "WAITING", "DONE", "NEXT"), records.map { it.values["TODO"] })
        assertEquals("Future 1", records[0].values["Future"])
    }

    @Test
    fun referencePassUsesActualStableTargetIds() {
        val samples = PreviewSampleGenerator(clock).generate(spec)
        val targetIds = samples.recordsFor("customers").map { it.id }
        val referenceIds = samples.recordsFor("orders").map { it.values.getValue("Customer") }

        assertEquals(targetIds, referenceIds)
        assertTrue(referenceIds.all(targetIds::contains))
        assertEquals(PreviewProvenance.Sample, samples.datasetFor("orders").provenance)
    }

    @Test
    fun missingReferenceTargetDegradesToBlankWithoutInventingAnId() {
        val broken = spec.copy(views = listOf(
            orders.copy(colTypes = orders.colTypes.map {
                if (it is ColType.Ref) ColType.Ref("missing") else it
            }),
        ))

        val records = PreviewSampleGenerator(clock).generate(broken).recordsFor("orders")

        assertTrue(records.all { it.values["Customer"].isNullOrEmpty() })
    }

    @Test
    fun recordCountIsKeptSmallAndChecklistDefaultsMixStates() {
        val checklistSpec = AppSpec(
            id = "checks",
            views = listOf(ViewSpec(title = "Steps", kind = ViewKind.CHECKLIST)),
        )

        val minimum = PreviewSampleGenerator(clock, requestedRecordCount = 1)
            .generate(checklistSpec)
            .recordsFor("steps")
        val maximum = PreviewSampleGenerator(clock, requestedRecordCount = 99)
            .generate(checklistSpec)
            .recordsFor("steps")

        assertEquals(3, minimum.size)
        assertEquals(5, maximum.size)
        assertEquals(listOf("[ ]", "[X]", "[ ]"), minimum.map { it.values["CHECKED"] })
    }

    @Test
    fun clockChangesRelativeDatesButNotRecordIdentity() {
        val laterClock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC)

        val july = PreviewSampleGenerator(clock).generate(spec).recordsFor("orders")
        val august = PreviewSampleGenerator(laterClock).generate(spec).recordsFor("orders")

        assertEquals(july.map { it.id }, august.map { it.id })
        assertNotEquals(july.map { it.values["Due"] }, august.map { it.values["Due"] })
    }
}
