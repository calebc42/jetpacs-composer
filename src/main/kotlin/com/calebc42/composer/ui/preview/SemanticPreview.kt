// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.preview.PreviewApp
import com.calebc42.composer.preview.PreviewDataMode
import com.calebc42.composer.preview.PreviewDataResolver
import com.calebc42.composer.preview.PreviewDataset
import com.calebc42.composer.preview.PreviewDestination
import com.calebc42.composer.preview.PreviewNotice
import com.calebc42.composer.preview.PreviewNoticeSeverity
import com.calebc42.composer.preview.PreviewProjection
import com.calebc42.composer.preview.PreviewProvenance
import com.calebc42.composer.preview.PreviewView
import com.calebc42.composer.preview.PreviewViewRoute

/**
 * Compact, read-only semantic preview of [spec]. Navigation and data mode are
 * preview-local; only route changes are reported to the editor through
 * [onSelectView].
 *
 * [selectedViewIndex] is optional so selecting the app form can leave the last
 * preview route visible. Supplying a view index keeps outline/form and preview
 * navigation synchronized in both directions.
 */
@Composable
fun SemanticPreview(
    spec: AppSpec,
    selectedViewIndex: Int? = null,
    onSelectView: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = remember(spec) { PreviewProjection.project(spec) }
    var route by remember { mutableStateOf<PreviewViewRoute?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var dataMode by remember { mutableStateOf(PreviewDataMode.AUTO) }
    val groupRoutes = remember { mutableStateMapOf<String, PreviewViewRoute>() }

    LaunchedEffect(app, selectedViewIndex) {
        val editorRoute = selectedViewIndex?.let { app.views.getOrNull(it)?.route }
        route = if (editorRoute != null) {
            editorRoute
        } else {
            PreviewProjection.reconcileRoute(app, route)
        }

        // Keep remembered group pages stable across rename/reorder edits, and
        // remove pages for groups that disappeared.
        val liveGroups = app.groups.map { it.slug }.toSet()
        groupRoutes.keys.toList().filterNot(liveGroups::contains).forEach(groupRoutes::remove)
        app.groups.forEach { destination ->
            val previous = groupRoutes[destination.slug]
            groupRoutes[destination.slug] = reconcileMember(app, destination, previous)
                ?: destination.members.firstOrNull()
                ?: return@forEach
        }
        route?.let { selected ->
            app.groups.firstOrNull { selected in it.members }?.let { destination ->
                groupRoutes[destination.slug] = selected
            }
        }
    }

    fun select(next: PreviewViewRoute) {
        route = PreviewProjection.reconcileRoute(app, next)
        val selected = route ?: return
        app.groups.firstOrNull { selected in it.members }?.let { destination ->
            groupRoutes[destination.slug] = selected
        }
        drawerOpen = false
        onSelectView(selected.index)
    }

    val selectedView = route?.let(app::view)
    val dataset = remember(spec, selectedView?.route, dataMode) {
        selectedView?.let { PreviewDataResolver.resolve(spec, it.route.index, dataMode) }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 3.dp,
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                PreviewTopBar(
                    app = app,
                    selectedView = selectedView,
                    hasDrawer = app.drawer.isNotEmpty(),
                    dataMode = dataMode,
                    onDataMode = { dataMode = it },
                    onOpenDrawer = { drawerOpen = true },
                )
                HorizontalDivider()

                selectedView?.group?.let { groupName ->
                    app.groups.firstOrNull { it.groupName == groupName }?.let { destination ->
                        GroupMemberSelector(
                            app = app,
                            destination = destination,
                            selected = selectedView.route,
                            onSelect = ::select,
                        )
                        HorizontalDivider()
                    }
                }

                PreviewBody(
                    app = app,
                    view = selectedView,
                    dataset = dataset,
                    modifier = Modifier.weight(1f),
                )

                if (app.tabs.isNotEmpty()) {
                    HorizontalDivider()
                    PreviewBottomTabs(
                        app = app,
                        route = route,
                        groupRoutes = groupRoutes,
                        onSelect = ::select,
                    )
                }
            }

            if (drawerOpen) {
                PreviewDrawer(
                    app = app,
                    selected = route,
                    onClose = { drawerOpen = false },
                    onSelect = ::select,
                )
            }
        }
    }
}

@Composable
private fun PreviewTopBar(
    app: PreviewApp,
    selectedView: PreviewView?,
    hasDrawer: Boolean,
    dataMode: PreviewDataMode,
    onDataMode: (PreviewDataMode) -> Unit,
    onOpenDrawer: () -> Unit,
) {
    var modeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasDrawer) {
            IconButton(onClick = onOpenDrawer, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.Menu, contentDescription = "Open preview navigation")
            }
        } else {
            Text(iconToken(app.icon), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            selectedView?.let {
                Text(
                    "${iconToken(it.icon)} ${it.title}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            OutlinedButton(onClick = { modeMenu = true }) {
                Text(dataMode.label, maxLines = 1)
            }
            DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                PreviewDataMode.entries.forEach { mode ->
                    DropdownMenuItem(
                        text = { Text(mode.label) },
                        onClick = {
                            onDataMode(mode)
                            modeMenu = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupMemberSelector(
    app: PreviewApp,
    destination: PreviewDestination,
    selected: PreviewViewRoute,
    onSelect: (PreviewViewRoute) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        destination.members.forEach { member ->
            val view = app.view(member)
            if (view != null) {
                if (member == selected) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Text(
                            view.title,
                            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                } else {
                    TextButton(onClick = { onSelect(member) }) { Text(view.title) }
                }
            }
        }
    }
}

@Composable
private fun PreviewBody(
    app: PreviewApp,
    view: PreviewView?,
    dataset: PreviewDataset?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (view == null || dataset == null) {
            Text("No views yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Add a view to see its navigation and content here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            app.notices.take(3).forEach { NoticeBadge(it) }
            return@Column
        }

        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(view.renderer.name.lowercase().replaceFirstChar(Char::titlecase),
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            ProvenanceBadge(dataset.provenance)
        }

        (view.notices + dataset.notices).distinctBy { it.code to it.message }
            .take(4).forEach { NoticeBadge(it) }

        Text(
            when (dataset.records.size) {
                0 -> "Empty preview"
                1 -> "1 record"
                else -> "${dataset.records.size} records"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (dataset.records.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Text(
                    "This is how the view looks without content.",
                    Modifier.fillMaxWidth().padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            dataset.records.take(6).forEach { record ->
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(record.title.ifBlank { "Untitled" }, fontWeight = FontWeight.SemiBold)
                        record.values.entries.take(3).forEach { (key, value) ->
                            val label = view.fields.firstOrNull { it.key == key }?.label ?: key
                            Text(
                                "$label: ${value.ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            if (dataset.records.size > 6) {
                Text(
                    "+ ${dataset.records.size - 6} more",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun PreviewBottomTabs(
    app: PreviewApp,
    route: PreviewViewRoute?,
    groupRoutes: Map<String, PreviewViewRoute>,
    onSelect: (PreviewViewRoute) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        app.tabs.forEach { destination ->
            val selected = route != null && route in destination.members
            val next = if (destination.isGroup) {
                groupRoutes[destination.slug]?.takeIf { it in destination.members }
                    ?: destination.members.firstOrNull()
            } else destination.members.firstOrNull()
            TextButton(
                onClick = { next?.let(onSelect) },
                enabled = next != null,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(iconToken(destination.icon))
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewDrawer(
    app: PreviewApp,
    selected: PreviewViewRoute?,
    onClose: () -> Unit,
    onSelect: (PreviewViewRoute) -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        Surface(
            Modifier.width(248.dp).fillMaxHeight(),
            shadowElevation = 10.dp,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(Modifier.fillMaxSize().padding(8.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${iconToken(app.icon)} ${app.label}",
                        Modifier.weight(1f).padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close preview navigation")
                    }
                }
                HorizontalDivider()
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    app.drawer.forEach { destination ->
                        val member = destination.members.firstOrNull()
                        val isSelected = member == selected
                        TextButton(
                            onClick = { member?.let(onSelect) },
                            enabled = member != null,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "${iconToken(destination.icon)}  ${destination.label}",
                                Modifier.fillMaxWidth(),
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxHeight()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)),
        )
    }
}

@Composable
private fun ProvenanceBadge(provenance: PreviewProvenance) {
    val (label, color) = when (provenance) {
        PreviewProvenance.Inline -> "Inline data" to MaterialTheme.colorScheme.primaryContainer
        PreviewProvenance.Sample -> "Sample data" to MaterialTheme.colorScheme.tertiaryContainer
        is PreviewProvenance.Local -> "Local: ${provenance.path}" to MaterialTheme.colorScheme.secondaryContainer
        PreviewProvenance.Empty -> "Empty" to MaterialTheme.colorScheme.surfaceVariant
    }
    Badge(label, color)
}

@Composable
private fun NoticeBadge(notice: PreviewNotice) {
    val color = when (notice.severity) {
        PreviewNoticeSeverity.INFO -> MaterialTheme.colorScheme.secondaryContainer
        PreviewNoticeSeverity.WARNING -> MaterialTheme.colorScheme.tertiaryContainer
        PreviewNoticeSeverity.ERROR -> MaterialTheme.colorScheme.errorContainer
    }
    Badge(notice.message, color)
}

@Composable
private fun Badge(label: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color) {
        Text(
            label,
            Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun reconcileMember(
    app: PreviewApp,
    destination: PreviewDestination,
    previous: PreviewViewRoute?,
): PreviewViewRoute? {
    if (previous == null) return destination.members.firstOrNull()
    return destination.members.firstOrNull { it.slug == previous.slug }
        ?: destination.members.firstOrNull { it.index == previous.index }
        ?: destination.members.firstOrNull()
}

private val PreviewDataMode.label: String
    get() = when (this) {
        PreviewDataMode.AUTO -> "Auto data"
        PreviewDataMode.SAMPLE -> "Sample data"
        PreviewDataMode.EMPTY -> "Empty state"
    }

private fun iconToken(icon: String): String = icon.ifBlank { "apps" }
    .replace('_', ' ')
    .replaceFirstChar(Char::titlecase)
