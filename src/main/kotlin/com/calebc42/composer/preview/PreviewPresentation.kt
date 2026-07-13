// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.ColType

/** Display value used by the semantic renderer without changing the raw org value. */
data class PreviewDisplayValue(
    val text: String,
    val checked: Boolean? = null,
)

data class PreviewResolvedReference(
    val label: String,
    val targetView: PreviewView?,
    val record: PreviewRecord?,
) {
    val resolved: Boolean get() = targetView != null && record != null
}

/** Pure formatting shared by list, table, and detail renderers. */
object PreviewPresentation {
    fun value(type: ColType, raw: String): PreviewDisplayValue = when (type) {
        ColType.Checkbox -> {
            val checked = raw.trim().equals("[X]", ignoreCase = true) ||
                raw.trim().equals("true", ignoreCase = true) || raw.trim() == "1"
            PreviewDisplayValue(if (checked) "Checked" else "Not checked", checked)
        }
        ColType.Date -> PreviewDisplayValue(
            DATE.find(raw)?.groupValues?.get(1) ?: raw.ifBlank { EMPTY },
        )
        else -> PreviewDisplayValue(raw.ifBlank { EMPTY })
    }

    fun recordValue(record: PreviewRecord, field: PreviewField): String =
        record.values.entries.firstOrNull { it.key.equals(field.key, ignoreCase = true) }?.value
            ?: if (field.key.equals("ITEM", ignoreCase = true)) record.title else ""

    fun resolveReference(
        app: PreviewApp,
        datasets: Map<String, PreviewDataset>,
        type: ColType.Ref,
        rawId: String,
        fallbackDatasets: Map<String, PreviewDataset> = emptyMap(),
    ): PreviewResolvedReference {
        val view = app.views.firstOrNull { it.route.slug == type.targetView }
        val record = datasets[type.targetView]?.records?.firstOrNull { it.id == rawId }
            ?: fallbackDatasets[type.targetView]?.records?.firstOrNull { it.id == rawId }
        val display = record?.let { target ->
            type.displayField?.let { key ->
                target.values.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value
            }?.takeIf(String::isNotBlank) ?: target.title
        }
        return PreviewResolvedReference(
            label = display?.takeIf(String::isNotBlank) ?: rawId.ifBlank { EMPTY },
            targetView = view,
            record = record,
        )
    }

    private const val EMPTY = "\u2014"
    private val DATE = Regex("(\\d{4}-\\d{2}-\\d{2})")
}
