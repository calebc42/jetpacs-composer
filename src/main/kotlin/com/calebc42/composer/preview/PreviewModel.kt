// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.AggregateOp
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.DateReminderRule
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind

/**
 * Pure, preview-only model derived from an AppSpec.  These values are safe to
 * hold in Compose state: they contain no editor callbacks, filesystem handles,
 * or runtime actions.
 */
data class PreviewApp(
    val id: String,
    val label: String,
    val icon: String,
    val order: Int,
    /** All destinations in effective runtime order, including drawer items. */
    val destinations: List<PreviewDestination>,
    val views: List<PreviewView>,
    /** All validation/projection notices; view-scoped notices also live on their view. */
    val notices: List<PreviewNotice> = emptyList(),
) {
    val tabs: List<PreviewDestination>
        get() = destinations.filter { it.placement == PreviewPlacement.TAB }

    val drawer: List<PreviewDestination>
        get() = destinations.filter { it.placement == PreviewPlacement.DRAWER }

    val groups: List<PreviewDestination>
        get() = tabs.filter { it.isGroup }

    fun view(route: PreviewViewRoute): PreviewView? =
        views.firstOrNull { it.route == route }
}

/** A stable-enough preview route: slug first, document index while the slug is edited. */
data class PreviewViewRoute(
    val slug: String,
    val index: Int,
)

enum class PreviewPlacement { TAB, DRAWER }

/**
 * One app-shell destination. A grouped destination contains two or more views
 * in normal documents, but remains renderable with one member in incomplete
 * specs so the preview never needs a special failure path.
 */
data class PreviewDestination(
    val slug: String,
    val label: String,
    val icon: String,
    val order: Int,
    val placement: PreviewPlacement,
    val members: List<PreviewViewRoute>,
    val groupName: String? = null,
) {
    val isGroup: Boolean get() = groupName != null
}

/** Normalized metadata shared by every semantic view renderer. */
data class PreviewView(
    val route: PreviewViewRoute,
    val title: String,
    val icon: String,
    val order: Int,
    val kind: ViewKind,
    val renderer: PreviewRendererToken,
    val placement: PreviewPlacement,
    val group: String?,
    val source: PreviewSource,
    val fields: List<PreviewField>,
    val actions: List<PreviewAction>,
    val filter: String?,
    val groupBy: String?,
    val dateField: String?,
    val imageField: String?,
    val metrics: List<PreviewMetric>,
    val reminder: DateReminderRule?,
    val notices: List<PreviewNotice> = emptyList(),
)

/** Exhaustive renderer-dispatch token. Adding a ViewKind makes its mapping fail to compile. */
enum class PreviewRendererToken {
    TABLE,
    CHECKLIST,
    RECORDS,
    NOTES,
    BOARD,
    CALENDAR,
    GALLERY,
    TREE,
    DASHBOARD,
    GANTT,
    UNSUPPORTED,
    ;

    companion object {
        fun from(kind: ViewKind): PreviewRendererToken = when (kind) {
            ViewKind.TABLE -> TABLE
            ViewKind.CHECKLIST -> CHECKLIST
            ViewKind.RECORDS -> RECORDS
            ViewKind.NOTES -> NOTES
            ViewKind.BOARD -> BOARD
            ViewKind.CALENDAR -> CALENDAR
            ViewKind.GALLERY -> GALLERY
            ViewKind.TREE -> TREE
            ViewKind.DASHBOARD -> DASHBOARD
            ViewKind.GANTT -> GANTT
            ViewKind.UNKNOWN -> UNSUPPORTED
        }
    }
}

data class PreviewField(
    val key: String,
    val label: String,
    val type: ColType,
    val index: Int,
)

data class PreviewAction(
    val token: String,
    val label: String,
    /** Preview controls demonstrate the affordance but never run the runtime action. */
    val previewOnly: Boolean = true,
    val unknown: Boolean = false,
)

data class PreviewMetric(
    val operation: AggregateOp,
    val field: String?,
    val label: String,
)

sealed interface PreviewSource {
    data object Inline : PreviewSource
    data class File(val file: String, val heading: String?) : PreviewSource
    data class Directory(val directory: String) : PreviewSource
    data class Pack(val packId: String, val source: String) : PreviewSource

    companion object {
        fun from(source: SourceRef?): PreviewSource = when (source) {
            null -> Inline
            is SourceRef.File -> File(source.file, source.heading)
            is SourceRef.Dir -> Directory(source.dir)
            is SourceRef.Pack -> Pack(source.packId, source.source)
        }
    }
}

enum class PreviewNoticeSeverity { INFO, WARNING, ERROR }

data class PreviewNotice(
    val message: String,
    val severity: PreviewNoticeSeverity,
    val viewIndex: Int? = null,
    /** Stable category for renderer filtering without parsing human text. */
    val code: String = "validation",
)

/** Visible provenance carried by every preview dataset. */
sealed interface PreviewProvenance {
    data object Inline : PreviewProvenance
    data object Sample : PreviewProvenance
    data class Local(val path: String) : PreviewProvenance
    data object Empty : PreviewProvenance
}

/**
 * A renderer-independent record. Values are deliberately raw strings: field
 * types and formatting live in [PreviewField], preserving exact inline org
 * text and stable reference IDs.
 */
data class PreviewRecord(
    val id: String,
    val title: String,
    val values: Map<String, String>,
    val body: String? = null,
)

data class PreviewDataset(
    val provenance: PreviewProvenance,
    val records: List<PreviewRecord>,
    val notices: List<PreviewNotice> = emptyList(),
)
