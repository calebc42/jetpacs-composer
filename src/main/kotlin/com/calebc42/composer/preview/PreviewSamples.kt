// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A deterministic, schema-aware sample dataset for every view in an app. */
data class PreviewSampleApp(
    val generatedOn: LocalDate,
    val recordsByViewSlug: Map<String, List<PreviewRecord>>,
) {
    fun recordsFor(viewSlug: String): List<PreviewRecord> =
        recordsByViewSlug[viewSlug].orEmpty()

    fun datasetFor(viewSlug: String): PreviewDataset = PreviewDataset(
        provenance = PreviewProvenance.Sample,
        records = recordsFor(viewSlug),
    )
}

/**
 * Produces small representative datasets without reading or mutating a source.
 *
 * [clock] is deliberately injectable: date samples retain their useful
 * overdue/today/upcoming relationship while pure tests and screenshots can pin
 * the day. The default is fixed as well, so calling this class directly is
 * reproducible across machines and runs.
 */
class PreviewSampleGenerator(
    private val clock: Clock = DEFAULT_CLOCK,
    requestedRecordCount: Int = DEFAULT_RECORD_COUNT,
) {
    private val recordCount = requestedRecordCount.coerceIn(MIN_RECORD_COUNT, MAX_RECORD_COUNT)

    fun generate(spec: AppSpec): PreviewSampleApp {
        val generatedOn = LocalDate.now(clock)
        val fieldsByView = spec.views.associate { it.name to sampleFields(spec, it) }

        // Generate stable IDs and all non-reference values first. References are
        // resolved below only after every possible target dataset exists.
        val firstPass = spec.views.associate { view ->
            val fields = fieldsByView.getValue(view.name)
            view.name to List(recordCount) { index ->
                firstPassRecord(spec, view, fields, index, generatedOn)
            }
        }

        val resolved = spec.views.associate { view ->
            val fields = fieldsByView.getValue(view.name)
            val records = firstPass.getValue(view.name).mapIndexed { index, record ->
                val values = record.values.toMutableMap()
                fields.forEach { field ->
                    val ref = field.type as? ColType.Ref ?: return@forEach
                    val targets = firstPass[ref.targetView].orEmpty()
                    values[field.prop] = targets.getOrNull(index % targets.size.coerceAtLeast(1))?.id.orEmpty()
                }
                record.copy(values = values)
            }
            view.name to records
        }

        return PreviewSampleApp(generatedOn, resolved)
    }

    private fun firstPassRecord(
        spec: AppSpec,
        view: ViewSpec,
        fields: List<SampleField>,
        index: Int,
        generatedOn: LocalDate,
    ): PreviewRecord {
        val ordinal = index + 1
        val id = "${spec.id}-${view.name}-sample-${ordinal.toString().padStart(2, '0')}"
        val values = linkedMapOf<String, String>()
        fields.forEach { field ->
            values[field.prop] = when {
                field.prop.equals("ID", ignoreCase = true) -> id
                field.type is ColType.Ref -> ""
                else -> sampleValue(spec, view, field, index, generatedOn)
            }
        }
        val title = values.entries.firstOrNull { it.key.equals("ITEM", ignoreCase = true) }
            ?.value
            ?.takeIf(String::isNotBlank)
            ?: "${view.title.ifBlank { "Record" }} item $ordinal"
        return PreviewRecord(id = id, title = title, values = values)
    }

    private fun sampleValue(
        spec: AppSpec,
        view: ViewSpec,
        field: SampleField,
        index: Int,
        generatedOn: LocalDate,
    ): String {
        val ordinal = index + 1
        if (field.prop.equals("ITEM", ignoreCase = true)) {
            return "${view.title.ifBlank { "Record" }} item $ordinal"
        }

        return when (val type = field.type) {
            ColType.Text -> "${field.label.ifBlank { "Value" }} $ordinal"
            ColType.Number -> {
                val seed = positiveHash("${spec.id}|${view.name}|${field.prop}")
                val base = 3 + seed % 13
                (base * ordinal).toString()
            }
            ColType.Date -> {
                val offset = DATE_OFFSETS[index % DATE_OFFSETS.size]
                "<${generatedOn.plusDays(offset.toLong()).format(ORG_DATE_FORMAT)}>"
            }
            ColType.Checkbox -> if (index % 2 == 0) "[ ]" else "[X]"
            is ColType.Enum -> enumValue(spec, field, type, index)
            is ColType.Ref -> "" // resolved after all target records exist
            is ColType.Unknown -> "${field.label.ifBlank { type.token.ifBlank { "Value" } }} $ordinal"
        }
    }

    private fun enumValue(
        spec: AppSpec,
        field: SampleField,
        type: ColType.Enum,
        index: Int,
    ): String {
        val options = if (field.prop.equals("TODO", ignoreCase = true) && spec.todoSequence.isNotEmpty()) {
            spec.todoSequence.map { it.keyword }
        } else {
            type.options
        }
        return options.getOrNull(index % options.size.coerceAtLeast(1)).orEmpty()
    }

    private fun sampleFields(spec: AppSpec, view: ViewSpec): List<SampleField> {
        val declared = when {
            view.schema.isNotEmpty() -> view.schema
            view.columns.isNotEmpty() -> view.columns.map(SchemaField::of)
            else -> view.body.filterIsInstance<BodyElement.Table>()
                .firstOrNull()
                ?.header
                ?.map(SchemaField::of)
                .orEmpty()
        }.ifEmpty {
            if (view.kind == ViewKind.CHECKLIST) {
                listOf(SchemaField("ITEM", "Item"), SchemaField("CHECKED", "Complete"))
            } else {
                listOf(SchemaField("ITEM", "Name"))
            }
        }

        return declared.mapIndexed { index, field ->
            val type = view.colTypes.getOrNull(index)
                ?: if (field.prop.equals("TODO", ignoreCase = true) && spec.todoSequence.isNotEmpty()) {
                    ColType.Enum(spec.todoSequence.map { it.keyword })
                } else if (field.prop.equals("CHECKED", ignoreCase = true)) {
                    ColType.Checkbox
                } else {
                    SchemaField.ORG_BUILTINS[field.prop.uppercase()]?.defaultType ?: ColType.Text
                }
            SampleField(
                prop = field.prop,
                label = field.label?.takeIf { it.isNotBlank() } ?: humanize(field.prop),
                type = type,
            )
        }
    }

    private fun humanize(prop: String): String = prop
        .replace('_', ' ')
        .lowercase()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ENGLISH) else it.toString() }

    private fun positiveHash(value: String): Int = value.hashCode() and Int.MAX_VALUE

    private data class SampleField(
        val prop: String,
        val label: String,
        val type: ColType,
    )

    companion object {
        const val MIN_RECORD_COUNT = 3
        const val DEFAULT_RECORD_COUNT = 4
        const val MAX_RECORD_COUNT = 5

        val DEFAULT_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-01-15T12:00:00Z"),
            ZoneOffset.UTC,
        )

        private val DATE_OFFSETS = listOf(-3, 0, 4, 10, 17)
        private val ORG_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd EEE", Locale.ENGLISH)
    }
}
