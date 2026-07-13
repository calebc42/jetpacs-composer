// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec

/** User-selectable source mode for the semantic preview. */
enum class PreviewDataMode { AUTO, SAMPLE, EMPTY }

/**
 * Pure P0 data coordinator. AUTO uses exact inline data and visibly falls back
 * to deterministic samples for external/device-only sources. Local filesystem
 * reads are a later, opt-in phase and deliberately do not occur here.
 */
object PreviewDataResolver {
    fun resolve(
        spec: AppSpec,
        viewIndex: Int,
        mode: PreviewDataMode = PreviewDataMode.AUTO,
        samples: PreviewSampleApp = PreviewSampleGenerator().generate(spec),
    ): PreviewDataset {
        val view = spec.views.getOrNull(viewIndex)
            ?: return PreviewDataset(
                provenance = PreviewProvenance.Empty,
                records = emptyList(),
                notices = listOf(PreviewNotice(
                    message = "The selected view no longer exists.",
                    severity = PreviewNoticeSeverity.WARNING,
                    viewIndex = viewIndex,
                    code = "missing-view",
                )),
            )

        return when (mode) {
            PreviewDataMode.EMPTY -> emptyDataset()
            PreviewDataMode.SAMPLE -> samples.datasetFor(view.name)
            PreviewDataMode.AUTO -> resolveAuto(spec, view, viewIndex, samples)
        }
    }

    private fun resolveAuto(
        spec: AppSpec,
        view: ViewSpec,
        viewIndex: Int,
        samples: PreviewSampleApp,
    ): PreviewDataset {
        if (view.source != null) {
            return samples.datasetFor(view.name).copy(notices = listOf(
                PreviewNotice(
                    message = "External/device source is represented with deterministic sample data.",
                    severity = PreviewNoticeSeverity.INFO,
                    viewIndex = viewIndex,
                    code = "sample-external-source",
                ),
            ))
        }

        return when (view.kind) {
            ViewKind.TABLE -> inlineTable(view)
            ViewKind.CHECKLIST -> inlineChecklist(view)
            ViewKind.RECORDS,
            ViewKind.BOARD,
            ViewKind.CALENDAR,
            ViewKind.GALLERY,
            ViewKind.TREE,
            ViewKind.DASHBOARD,
            ViewKind.GANTT,
            -> inlineOrgRecords(spec, view)
            // Notes require a vault at runtime. Invalid/incomplete inline notes
            // still remain previewable, but are never presented as actual data.
            ViewKind.NOTES -> samples.datasetFor(view.name).copy(notices = listOf(
                PreviewNotice(
                    message = "Notes require a runtime vault; showing sample records.",
                    severity = PreviewNoticeSeverity.INFO,
                    viewIndex = viewIndex,
                    code = "sample-notes",
                ),
            ))
            ViewKind.UNKNOWN -> PreviewDataset(
                provenance = PreviewProvenance.Empty,
                records = emptyList(),
                notices = listOf(PreviewNotice(
                    message = "Unknown views have no preview dataset.",
                    severity = PreviewNoticeSeverity.WARNING,
                    viewIndex = viewIndex,
                    code = "unsupported-kind",
                )),
            )
        }
    }

    private fun inlineTable(view: ViewSpec): PreviewDataset {
        val table = view.body.filterIsInstance<BodyElement.Table>().firstOrNull()
            ?: return emptyDataset()
        val records = table.rows.mapIndexed { rowIndex, row ->
            val values = table.header.mapIndexed { columnIndex, header ->
                header to row.getOrElse(columnIndex) { "" }
            }.toMap(linkedMapOf())
            PreviewRecord(
                id = "${view.name}-inline-row-${rowIndex + 1}",
                title = row.firstOrNull().orEmpty().ifBlank { "Row ${rowIndex + 1}" },
                values = values,
            )
        }
        return inlineOrEmpty(records)
    }

    private fun inlineChecklist(view: ViewSpec): PreviewDataset {
        val checklist = view.body.filterIsInstance<BodyElement.Checklist>().firstOrNull()
            ?: return emptyDataset()
        val records = checklist.items.mapIndexed { index, item ->
            PreviewRecord(
                id = "${view.name}-inline-item-${index + 1}",
                title = item.text,
                values = linkedMapOf("ITEM" to item.text, "CHECKED" to "[${item.state}]"),
            )
        }
        return inlineOrEmpty(records)
    }

    private fun inlineOrgRecords(spec: AppSpec, view: ViewSpec): PreviewDataset {
        val raw = view.body.filterIsInstance<BodyElement.Raw>()
            .joinToString("\n") { it.text }
        if (raw.isBlank()) return emptyDataset()
        val result = PreviewOrgRecords.read(
            raw,
            PreviewOrgReadOptions(
                traversal = if (view.kind == ViewKind.TREE) {
                    PreviewOrgTraversal.DESCENDANTS
                } else {
                    PreviewOrgTraversal.DIRECT_CHILDREN
                },
                unscopedParentLevel = 1,
                todoKeywords = spec.todoSequence.map { it.keyword }.toSet(),
            ),
        )
        return if (result.records.isEmpty()) emptyDataset() else result.asInlineDataset()
    }

    private fun inlineOrEmpty(records: List<PreviewRecord>): PreviewDataset =
        if (records.isEmpty()) emptyDataset()
        else PreviewDataset(PreviewProvenance.Inline, records)

    private fun emptyDataset(): PreviewDataset =
        PreviewDataset(PreviewProvenance.Empty, emptyList())
}
