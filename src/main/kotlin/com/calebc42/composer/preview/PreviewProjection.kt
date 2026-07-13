// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.ActionDef
import com.calebc42.composer.model.AggregateOp
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewNav
import com.calebc42.composer.model.ViewSpec

/** Pure AppSpec -> normalized semantic-preview projection. */
object PreviewProjection {

    fun project(spec: AppSpec): PreviewApp {
        val problems = runCatching { ModelOps.validate(spec) }
            .getOrElse { error ->
                listOf(ModelOps.Problem(
                    message = "Preview validation could not finish: " +
                        (error.message ?: error::class.simpleName.orEmpty()),
                    severity = ModelOps.Severity.Error,
                ))
            }
        val validationNotices = problems.map(::noticeFrom)

        val views = spec.views.mapIndexed { index, view ->
            projectView(
                view = view,
                index = index,
                validationNotices = validationNotices.filter { it.viewIndex == index },
            )
        }

        return PreviewApp(
            id = spec.id,
            label = spec.label ?: defaultAppLabel(spec.id),
            icon = spec.icon ?: "apps",
            order = spec.order ?: 100,
            destinations = projectDestinations(views),
            views = views,
            notices = validationNotices + views.flatMap { view ->
                view.notices.filter { it.code != "validation" }
            },
        )
    }

    /**
     * Reconcile a preview-local selection after an edit or undo. Slug identity
     * wins; the old document index keeps the same view selected while its title
     * (and therefore slug) is changing.
     */
    fun reconcileRoute(
        app: PreviewApp,
        previous: PreviewViewRoute?,
    ): PreviewViewRoute? {
        if (app.views.isEmpty()) return null
        if (previous == null) return app.views.first().route
        return app.views.firstOrNull { it.route.slug == previous.slug }?.route
            ?: app.views.getOrNull(previous.index)?.route
            ?: app.views.first().route
    }

    private fun projectView(
        view: ViewSpec,
        index: Int,
        validationNotices: List<PreviewNotice>,
    ): PreviewView {
        val unknownNotices = buildList {
            if (view.kind == ViewKind.UNKNOWN) {
                add(PreviewNotice(
                    message = "This view kind is unknown; its metadata is preserved but no semantic renderer is available.",
                    severity = PreviewNoticeSeverity.WARNING,
                    viewIndex = index,
                    code = "unsupported-kind",
                ))
            }
            view.colTypes.forEachIndexed { fieldIndex, type ->
                if (type is ColType.Unknown) {
                    add(PreviewNotice(
                        message = "Field ${fieldIndex + 1} uses unsupported type \"${type.token}\"; previewing it as text.",
                        severity = PreviewNoticeSeverity.WARNING,
                        viewIndex = index,
                        code = "unsupported-coltype",
                    ))
                }
            }
            view.actions.filterIsInstance<ActionDef.Unknown>().forEach { action ->
                add(PreviewNotice(
                    message = "Action \"${action.token}\" is not understood by the preview.",
                    severity = PreviewNoticeSeverity.WARNING,
                    viewIndex = index,
                    code = "unsupported-action",
                ))
            }
        }

        return PreviewView(
            route = PreviewViewRoute(view.name, index),
            title = view.title,
            icon = view.icon ?: defaultViewIcon(view.kind),
            order = effectiveViewOrder(view, index),
            kind = view.kind,
            renderer = PreviewRendererToken.from(view.kind),
            placement = if (view.group == null && view.nav == ViewNav.DRAWER) {
                PreviewPlacement.DRAWER
            } else {
                PreviewPlacement.TAB
            },
            group = view.group,
            source = PreviewSource.from(view.source),
            fields = projectFields(view),
            actions = view.actions.map(::projectAction),
            filter = view.filter,
            groupBy = view.groupBy,
            dateField = view.dateField,
            imageField = view.imageField,
            metrics = view.metrics.map { metric ->
                PreviewMetric(
                    operation = metric.operation,
                    field = metric.field,
                    label = when (metric.operation) {
                        AggregateOp.COUNT -> "Count"
                        AggregateOp.SUM -> "Sum ${metric.field.orEmpty()}".trimEnd()
                        AggregateOp.AVG -> "Average ${metric.field.orEmpty()}".trimEnd()
                    },
                )
            },
            reminder = view.reminder,
            notices = validationNotices + unknownNotices,
        )
    }

    private fun projectFields(view: ViewSpec): List<PreviewField> {
        val namesAndLabels = when (view.kind) {
            ViewKind.TABLE -> {
                val names = if (view.source == null) {
                    ModelOps.firstTable(view)?.header.orEmpty()
                } else {
                    view.columns
                }
                names.map { it to it }
            }
            ViewKind.CHECKLIST -> listOf("ITEM" to "Item")
            ViewKind.RECORDS,
            ViewKind.NOTES,
            ViewKind.BOARD,
            ViewKind.CALENDAR,
            ViewKind.GALLERY,
            ViewKind.TREE,
            ViewKind.DASHBOARD,
            ViewKind.GANTT,
            ViewKind.UNKNOWN,
            -> view.schema.map { field -> field.prop to (field.label ?: field.prop) }
        }
        return namesAndLabels.mapIndexed { index, (key, label) ->
            PreviewField(
                key = key,
                label = label,
                type = effectiveFieldType(view, key, index),
                index = index,
            )
        }
    }

    private fun effectiveFieldType(view: ViewSpec, key: String, index: Int): ColType {
        val declared = view.colTypes.getOrNull(index)
        if (declared != null) return declared
        return if (view.kind == ViewKind.CHECKLIST) {
            ColType.Checkbox
        } else {
            SchemaField.ORG_BUILTINS[key.uppercase()]?.defaultType ?: ColType.Text
        }
    }

    private fun projectDestinations(views: List<PreviewView>): List<PreviewDestination> {
        val ungrouped = views.filter { it.group == null }.map { view ->
            PreviewDestination(
                slug = view.route.slug,
                label = view.title,
                icon = view.icon,
                order = view.order,
                placement = view.placement,
                members = listOf(view.route),
            ) to view.route.index
        }

        // LinkedHashMap makes group destination and member ordering match the
        // runtime: first group occurrence fixes destination position, while
        // members retain document order regardless of their ORDER values.
        val groups = linkedMapOf<String, MutableList<PreviewView>>()
        views.forEach { view ->
            view.group?.let { group -> groups.getOrPut(group) { mutableListOf() }.add(view) }
        }
        val grouped = groups.map { (name, members) ->
            val first = members.first()
            PreviewDestination(
                slug = slug(name),
                label = name,
                icon = first.icon,
                order = members.minOf { it.order },
                placement = PreviewPlacement.TAB,
                members = members.map { it.route },
                groupName = name,
            ) to first.route.index
        }

        return (ungrouped + grouped)
            .sortedWith(compareBy<Pair<PreviewDestination, Int>> { it.first.order }
                .thenBy { it.second })
            .map { it.first }
    }

    private fun projectAction(action: ActionDef): PreviewAction = when (action) {
        is ActionDef.SetTodo -> PreviewAction(action.toToken(), "Set TODO to ${action.keyword}")
        is ActionDef.Schedule -> PreviewAction(action.toToken(), "Schedule")
        is ActionDef.SetDeadline -> PreviewAction(action.toToken(), "Set deadline")
        is ActionDef.SetTags -> PreviewAction(
            action.toToken(),
            if (action.tags.isEmpty()) "Set tags" else "Set tags: ${action.tags.joinToString(", ")}",
        )
        is ActionDef.SetPriority -> PreviewAction(
            action.toToken(),
            action.priority?.let { "Set priority $it" } ?: "Set priority",
        )
        is ActionDef.Refile -> PreviewAction(action.toToken(), "Refile")
        is ActionDef.Archive -> PreviewAction(action.toToken(), "Archive")
        is ActionDef.PackAction -> PreviewAction(action.toToken(), "${action.packId}/${action.action}")
        is ActionDef.Unknown -> PreviewAction(
            token = action.token,
            label = action.token.ifBlank { "Unknown action" },
            unknown = true,
        )
    }

    private fun noticeFrom(problem: ModelOps.Problem): PreviewNotice = PreviewNotice(
        message = problem.message,
        severity = when (problem.severity) {
            ModelOps.Severity.Error -> PreviewNoticeSeverity.ERROR
            ModelOps.Severity.Warning -> PreviewNoticeSeverity.WARNING
            ModelOps.Severity.Info -> PreviewNoticeSeverity.INFO
        },
        viewIndex = problem.viewIndex,
    )

    private fun effectiveViewOrder(view: ViewSpec, index: Int): Int =
        view.order ?: (index + 1) * 10

    /** Kotlin equivalent of the runtime parser's `(capitalize id)`. */
    private fun defaultAppLabel(id: String): String = id.split('-').joinToString("-") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun defaultViewIcon(kind: ViewKind): String = when (kind) {
        ViewKind.CHECKLIST -> "checklist"
        ViewKind.RECORDS -> "list_alt"
        ViewKind.NOTES -> "sticky_note_2"
        ViewKind.BOARD -> "view_kanban"
        ViewKind.CALENDAR -> "calendar_month"
        ViewKind.GALLERY -> "grid_view"
        ViewKind.TREE -> "account_tree"
        ViewKind.DASHBOARD -> "bar_chart"
        ViewKind.GANTT -> "view_timeline"
        ViewKind.TABLE, ViewKind.UNKNOWN -> "table_chart"
    }

    private fun slug(title: String): String = title.lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifEmpty { "view" }
}
