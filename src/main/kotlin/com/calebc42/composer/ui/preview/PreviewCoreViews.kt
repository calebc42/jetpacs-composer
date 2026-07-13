// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.preview.PreviewAction
import com.calebc42.composer.preview.PreviewApp
import com.calebc42.composer.preview.PreviewDataset
import com.calebc42.composer.preview.PreviewField
import com.calebc42.composer.preview.PreviewPresentation
import com.calebc42.composer.preview.PreviewRecord
import com.calebc42.composer.preview.PreviewRendererToken
import com.calebc42.composer.preview.PreviewView

internal data class PreviewDetailSelection(
    val view: PreviewView,
    val dataset: PreviewDataset,
    val record: PreviewRecord,
)

@Composable
internal fun PreviewCoreContent(
    app: PreviewApp,
    view: PreviewView,
    dataset: PreviewDataset,
    datasets: Map<String, PreviewDataset>,
    fallbackDatasets: Map<String, PreviewDataset>,
    onOpenDetail: (PreviewDetailSelection) -> Unit,
    onPreviewOnly: (String) -> Unit,
) {
    when (view.renderer) {
        PreviewRendererToken.TABLE -> PreviewTable(
            app, view, dataset, datasets, fallbackDatasets, onOpenDetail,
        )
        PreviewRendererToken.CHECKLIST -> PreviewChecklist(view, dataset, onOpenDetail, onPreviewOnly)
        PreviewRendererToken.RECORDS,
        PreviewRendererToken.NOTES,
        -> PreviewRecordCards(
            app, view, dataset, datasets, fallbackDatasets, onOpenDetail, onPreviewOnly,
        )
        else -> PreviewRecordCards(
            app, view, dataset, datasets, fallbackDatasets, onOpenDetail, onPreviewOnly,
            compact = true,
        )
    }
}

@Composable
private fun PreviewTable(
    app: PreviewApp,
    view: PreviewView,
    dataset: PreviewDataset,
    datasets: Map<String, PreviewDataset>,
    fallbackDatasets: Map<String, PreviewDataset>,
    onOpenDetail: (PreviewDetailSelection) -> Unit,
) {
    val fields = displayFields(view, dataset)
    Column(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            fields.forEach { field -> TableCell(field.label, header = true) }
        }
        dataset.records.forEach { record ->
            Row(
                Modifier.clickable { onOpenDetail(PreviewDetailSelection(view, dataset, record)) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                fields.forEach { field ->
                    Box(Modifier.width(TABLE_CELL_WIDTH).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        PreviewValue(app, field, record, datasets, fallbackDatasets, onOpenDetail)
                    }
                }
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun TableCell(text: String, header: Boolean = false) {
    Text(
        text,
        Modifier.width(TABLE_CELL_WIDTH).padding(horizontal = 10.dp, vertical = 8.dp),
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (header) FontWeight.SemiBold else null,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun PreviewChecklist(
    view: PreviewView,
    dataset: PreviewDataset,
    onOpenDetail: (PreviewDetailSelection) -> Unit,
    onPreviewOnly: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        dataset.records.forEach { record ->
            val raw = record.values.entries.firstOrNull {
                it.key.equals("CHECKED", ignoreCase = true)
            }?.value.orEmpty()
            val checked = PreviewPresentation.value(ColType.Checkbox, raw).checked == true
            Card(
                Modifier.fillMaxWidth().clickable {
                    onOpenDetail(PreviewDetailSelection(view, dataset, record))
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onPreviewOnly("Checklist changes are preview only.") },
                    )
                    Text(record.title.ifBlank { "Untitled item" }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PreviewRecordCards(
    app: PreviewApp,
    view: PreviewView,
    dataset: PreviewDataset,
    datasets: Map<String, PreviewDataset>,
    fallbackDatasets: Map<String, PreviewDataset>,
    onOpenDetail: (PreviewDetailSelection) -> Unit,
    onPreviewOnly: (String) -> Unit,
    compact: Boolean = false,
) {
    val fields = displayFields(view, dataset)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        dataset.records.take(if (compact) 6 else 12).forEach { record ->
            Card(
                Modifier.fillMaxWidth().clickable {
                    onOpenDetail(PreviewDetailSelection(view, dataset, record))
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(record.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold)
                    fields.filterNot { it.key.equals("ITEM", ignoreCase = true) }
                        .take(if (compact) 2 else 4).forEach { field ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    field.label,
                                    Modifier.width(88.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                PreviewValue(
                                    app, field, record, datasets, fallbackDatasets, onOpenDetail,
                                )
                            }
                        }
                    if (!compact && view.actions.isNotEmpty()) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            view.actions.take(2).forEach { action ->
                                TextButton(onClick = { onPreviewOnly(previewOnlyMessage(action)) }) {
                                    Text(action.label, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewValue(
    app: PreviewApp,
    field: PreviewField,
    record: PreviewRecord,
    datasets: Map<String, PreviewDataset>,
    fallbackDatasets: Map<String, PreviewDataset>,
    onOpenDetail: (PreviewDetailSelection) -> Unit,
) {
    val raw = PreviewPresentation.recordValue(record, field)
    when (val type = field.type) {
        ColType.Checkbox -> Checkbox(
            checked = PreviewPresentation.value(type, raw).checked == true,
            onCheckedChange = null,
        )
        is ColType.Ref -> {
            val resolved = PreviewPresentation.resolveReference(
                app, datasets, type, raw, fallbackDatasets,
            )
            TextButton(
                onClick = {
                    val targetView = resolved.targetView
                    val targetRecord = resolved.record
                    if (targetView != null && targetRecord != null) {
                        val targetDataset = datasets[targetView.route.slug]
                            ?: fallbackDatasets[targetView.route.slug]
                            ?: PreviewDataset(datasetProvenance(datasets, fallbackDatasets), listOf(targetRecord))
                        onOpenDetail(PreviewDetailSelection(targetView, targetDataset, targetRecord))
                    }
                },
                enabled = resolved.resolved,
            ) { Text(resolved.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        is ColType.Enum -> ValueChip(PreviewPresentation.value(type, raw).text)
        else -> Text(
            PreviewPresentation.value(type, raw).text,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun PreviewDetailSheet(
    app: PreviewApp,
    selection: PreviewDetailSelection,
    datasets: Map<String, PreviewDataset>,
    fallbackDatasets: Map<String, PreviewDataset>,
    onOpenDetail: (PreviewDetailSelection) -> Unit,
    onPreviewOnly: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f))) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.88f).align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(selection.record.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleMedium)
                        Text(selection.view.title, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close record details")
                    }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    displayFields(selection.view, selection.dataset).forEach { field ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(field.label, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            PreviewValue(
                                app, field, selection.record, datasets, fallbackDatasets, onOpenDetail,
                            )
                        }
                    }
                    selection.record.body?.takeIf(String::isNotBlank)?.let { body ->
                        HorizontalDivider()
                        Text("Body", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (selection.view.actions.isNotEmpty()) {
                        HorizontalDivider()
                        Text("Actions", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        selection.view.actions.forEach { action ->
                            OutlinedButton(
                                onClick = { onPreviewOnly(previewOnlyMessage(action)) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(action.label) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ValueChip(label: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Text(label, Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall)
    }
}

private fun displayFields(view: PreviewView, dataset: PreviewDataset): List<PreviewField> {
    if (view.kind == ViewKind.CHECKLIST) {
        return listOf(
            PreviewField("ITEM", "Item", ColType.Text, 0),
            PreviewField("CHECKED", "Complete", ColType.Checkbox, 1),
        )
    }
    if (view.fields.isNotEmpty()) return view.fields
    return dataset.records.firstOrNull()?.values?.keys.orEmpty().mapIndexed { index, key ->
        PreviewField(key, key, ColType.Text, index)
    }
}

private fun previewOnlyMessage(action: PreviewAction): String =
    "${action.label} is preview only. No org data or device state was changed."

private fun datasetProvenance(
    datasets: Map<String, PreviewDataset>,
    fallbackDatasets: Map<String, PreviewDataset>,
) = datasets.values.firstOrNull()?.provenance
    ?: fallbackDatasets.values.firstOrNull()?.provenance
    ?: com.calebc42.composer.preview.PreviewProvenance.Empty

private val TABLE_CELL_WIDTH = 132.dp
