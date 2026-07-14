// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.ContractManifest
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.PackRegistry
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.project.ComposerConfig
import com.calebc42.composer.ui.preview.PreviewSplitPane
import com.calebc42.composer.ui.preview.PreviewSplitPaneState
import com.calebc42.composer.ui.preview.SemanticPreview
import java.io.File

/** What the detail pane is editing. */
sealed interface Selection {
    data object App : Selection
    data class View(val index: Int) : Selection
}

@Composable
fun EditorScreen(session: EditorSession, config: ComposerConfig,
                 onSettings: () -> Unit, onClose: () -> Unit) {
    var selection by remember { mutableStateOf<Selection>(Selection.App) }
    var sourcePreviewDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var previewVisible by remember(session) { mutableStateOf(true) }
    val previewPaneState = remember(session) { PreviewSplitPaneState() }
    var deploying by remember { mutableStateOf(false) }
    var browsingFiles by remember { mutableStateOf(false) }

    val packRegistry = remember(config.packDirectory) {
        PackRegistry.load(config.packDirectory?.let(::File))
    }
    val problems = ModelOps.validate(
        session.spec,
        nodeTypes = ContractManifest.contract.node_types.toSet(),
        packs = packRegistry,
    )
    val hasErrors = problems.any { it.severity == ModelOps.Severity.Error }

    fun moveHistory(move: () -> Boolean) {
        val selectedIndex = (selection as? Selection.View)?.index
        val selectedName = selectedIndex?.let { session.spec.views.getOrNull(it)?.name }
        if (!move()) return
        selection = selectedName?.let { name ->
            session.spec.views.indexOfFirst { it.name == name }
                .takeIf { it >= 0 }?.let(Selection::View)
        } ?: selectedIndex?.takeIf { it in session.spec.views.indices }
            ?.let(Selection::View)
            ?: Selection.App
    }
    fun undo() = moveHistory(session::undo)
    fun redo() = moveHistory(session::redo)

    LaunchedEffect(session.spec.views.size, selection) {
        val selected = selection as? Selection.View
        if (selected != null && selected.index !in session.spec.views.indices) {
            selection = Selection.App
        }
    }

    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onPreviewKeyEvent false
            when {
                event.key == Key.Z && event.isShiftPressed -> { redo(); true }
                event.key == Key.Z -> { undo(); true }
                event.key == Key.Y -> { redo(); true }
                else -> false
            }
        },
    ) {
        // ── Toolbar ──────────────────────────────────────────────────────
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                (session.file?.name ?: "unsaved") + if (session.dirty) " •" else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 8.dp)
            )
            OutlinedButton(onClick = ::undo, enabled = session.canUndo) { Text("Undo") }
            OutlinedButton(onClick = ::redo, enabled = session.canRedo) { Text("Redo") }
            OutlinedButton(onClick = { previewVisible = !previewVisible }) {
                Text(if (previewVisible) "Hide preview" else "Preview")
            }
            OutlinedButton(onClick = { sourcePreviewDialog = session.documentText() to "org" }) {
                Text("app.org")
            }
            OutlinedButton(onClick = { sourcePreviewDialog = session.bundleText() to "elisp" }) {
                Text("Exported elisp")
            }
            Button(onClick = {
                if (session.file == null)
                    pickSaveFile("Save app document", "${session.spec.id}.org",
                                 config.defaultAppPath)
                        ?.let { session.save(it) }
                else session.save()
            }) { Text("Save") }
            Button(onClick = {
                if (session.file == null)
                    pickSaveFile("Save app document", "${session.spec.id}.org",
                                 config.defaultAppPath)
                        ?.let { session.save(it) }
                session.export(config.defaultExportPath?.let(::File))
            }, enabled = !hasErrors) { Text("Export bundle") }
            Button(onClick = { browsingFiles = true }) { Text("Device Files…") }
            Button(onClick = { deploying = true }, enabled = !hasErrors) { Text("Deploy…") }
            
            Spacer(Modifier.weight(1f))
            
            androidx.compose.material3.IconButton(onClick = onSettings) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
        HorizontalDivider()

        // ── Outline | Form | Semantic preview ────────────────────────────
        PreviewSplitPane(
            previewVisible = previewVisible,
            state = previewPaneState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            editorContent = {
                Row(Modifier.fillMaxSize()) {
                    OutlinePane(session, selection, onSelect = { selection = it })
                    VerticalDivider()
                    val scrollState = rememberScrollState()
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Column(Modifier.widthIn(max = 800.dp)) {
                                when (val sel = selection) {
                                    is Selection.App -> AppForm(session)
                                    is Selection.View ->
                                        if (sel.index in session.spec.views.indices)
                                            ViewForm(session, sel.index, packRegistry)
                                        else AppForm(session)
                                }
                            }
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(scrollState)
                        )
                    }
                }
            },
            previewContent = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    SemanticPreview(
                        spec = session.spec,
                        selectedViewIndex = (selection as? Selection.View)?.index,
                        onSelectView = { selection = Selection.View(it) },
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxHeight()
                            .widthIn(max = 450.dp)
                            .fillMaxWidth(),
                    )
                }
            },
        )

        // ── Problems strip ───────────────────────────────────────────────
        val error = session.lastError
        if (problems.isNotEmpty() || error != null) {
            HorizontalDivider()
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                error?.let {
                    Text("ERROR: $it", color = MaterialTheme.colorScheme.error)
                }
                problems.forEach { p ->
                    val color = when (p.severity) {
                        ModelOps.Severity.Error -> MaterialTheme.colorScheme.error
                        ModelOps.Severity.Warning -> MaterialTheme.colorScheme.tertiary
                        ModelOps.Severity.Info -> MaterialTheme.colorScheme.primary
                    }
                    Text(
                        "${p.severity.name.uppercase()}: " +
                            (p.viewIndex?.let { i ->
                            "${session.spec.views.getOrNull(i)?.title}: "
                        } ?: "") + p.message,
                        color = color,
                        modifier = Modifier.clickable {
                            p.viewIndex?.let { selection = Selection.View(it) }
                        },
                    )
                }
            }
        }
    }

    if (browsingFiles) DeviceFilesDialog(onDismiss = { browsingFiles = false })
    if (deploying) DeployDialog(session, onDismiss = { deploying = false })

    sourcePreviewDialog?.let { (text, type) ->
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        val annotatedText = remember(text, type) { highlightSyntax(text, type) }
        AlertDialog(
            onDismissRequest = { sourcePreviewDialog = null },
            confirmButton = {
                TextButton(onClick = { sourcePreviewDialog = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                }) { Text("Copy to Clipboard") }
            },
            title = { Text(if (type == "org") "app.org Preview" else "Exported Elisp Preview") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    val scrollState = rememberScrollState()
                    Box(Modifier.fillMaxWidth().height(420.dp)) {
                        Column(Modifier.fillMaxSize().verticalScroll(scrollState)) {
                            Text(
                                text = annotatedText,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        VerticalScrollbar(
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                            adapter = rememberScrollbarAdapter(scrollState)
                        )
                    }
                }
            },
        )
    }
}

private fun highlightSyntax(text: String, type: String): androidx.compose.ui.text.AnnotatedString {
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        if (type == "org") {
            // Very simple org-mode highlighting
            val lines = text.split("\n")
            var currentIndex = 0
            for (line in lines) {
                if (line.startsWith("*")) {
                    addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF2196F3), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), currentIndex, currentIndex + line.length)
                } else if (line.trim().startsWith(":") && line.trim().endsWith(":")) {
                    addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF4CAF50)), currentIndex, currentIndex + line.length)
                } else if (line.trim().startsWith("#+")) {
                    addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF9E9E9E)), currentIndex, currentIndex + line.length)
                }
                currentIndex += line.length + 1
            }
        } else if (type == "elisp") {
            // Very simple elisp highlighting
            val stringRegex = "\".*?\"".toRegex()
            val commentRegex = ";;.*".toRegex()
            val keywordRegex = "\\b(defun|setq|require|provide|let|if|when|cond)\\b".toRegex()
            
            for (match in stringRegex.findAll(text)) {
                addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF4CAF50)), match.range.first, match.range.last + 1)
            }
            for (match in commentRegex.findAll(text)) {
                addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF9E9E9E)), match.range.first, match.range.last + 1)
            }
            for (match in keywordRegex.findAll(text)) {
                addStyle(androidx.compose.ui.text.SpanStyle(color = androidx.compose.ui.graphics.Color(0xFF2196F3), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), match.range.first, match.range.last + 1)
            }
        }
    }
}

@Composable
private fun OutlinePane(
    session: EditorSession,
    selection: Selection,
    onSelect: (Selection) -> Unit,
) {
    Box(Modifier.width(260.dp).fillMaxHeight()) {
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize().padding(8.dp),
        ) {
            item {
                OutlineRow(
                    label = "App: ${session.spec.id}",
                    selected = selection == Selection.App,
                    onClick = { onSelect(Selection.App) }
                )
            }
            item {
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            itemsIndexed(session.spec.views) { i, view ->
                OutlineRow(
                    label = when (view.kind) {
                        ViewKind.CHECKLIST -> "☑ ${view.title}"
                        ViewKind.RECORDS -> "☰ ${view.title}"
                        ViewKind.TABLE -> "▦ ${view.title}"
                        ViewKind.NOTES -> "📝 ${view.title}"
                        ViewKind.BOARD -> "📋 ${view.title}"
                        ViewKind.CALENDAR -> "📅 ${view.title}"
                        ViewKind.GALLERY -> "🖼 ${view.title}"
                        ViewKind.TREE -> "🌳 ${view.title}"
                        ViewKind.DASHBOARD -> "📊 ${view.title}"
                        ViewKind.GANTT -> "▰ ${view.title}"
                        ViewKind.UNKNOWN -> "❓ ${view.title}"
                    },
                    selected = selection == Selection.View(i),
                    onClick = { onSelect(Selection.View(i)) },
                    onMoveUp = if (i > 0) { {
                        session.update { ModelOps.moveView(it, i, -1) }
                        onSelect(Selection.View(i - 1))
                    } } else null,
                    onMoveDown = if (i < session.spec.views.size - 1) { {
                        session.update { ModelOps.moveView(it, i, +1) }
                        onSelect(Selection.View(i + 1))
                    } } else null,
                    onDelete = {
                        session.update { ModelOps.removeView(it, i) }
                        onSelect(Selection.App)
                    }
                )
            }
            item {
                Spacer(Modifier.height(12.dp))
            }
            item {
                var addViewExpanded by remember { mutableStateOf(false) }
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { addViewExpanded = true }) { Text("+ View") }
                    androidx.compose.material3.DropdownMenu(
                        expanded = addViewExpanded,
                        onDismissRequest = { addViewExpanded = false },
                    ) {
                        ViewKind.entries.forEach { kind ->
                            val icon = when (kind) {
                                ViewKind.TABLE -> "▦"
                                ViewKind.CHECKLIST -> "☑"
                                ViewKind.RECORDS -> "☰"
                                ViewKind.NOTES -> "📝"
                                ViewKind.BOARD -> "📋"
                                ViewKind.CALENDAR -> "📅"
                                ViewKind.GALLERY -> "🖼"
                                ViewKind.TREE -> "🌳"
                                ViewKind.DASHBOARD -> "📊"
                                ViewKind.GANTT -> "▰"
                                ViewKind.UNKNOWN -> "❓"
                            }
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("$icon ${kind.name}") },
                                onClick = {
                                    session.update { ModelOps.addView(it, "New ${kind.name.lowercase()}", kind) }
                                    onSelect(Selection.View(session.spec.views.size))
                                    addViewExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
        VerticalScrollbar(
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            adapter = rememberScrollbarAdapter(state)
        )
    }
}

@Composable
private fun OutlineRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onMoveUp: (() -> Unit)? = null,
    onMoveDown: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (selected && (onMoveUp != null || onMoveDown != null || onDelete != null)) {
            if (onMoveUp != null) {
                androidx.compose.material3.IconButton(onClick = onMoveUp, modifier = Modifier.size(24.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move Up")
                }
            }
            if (onMoveDown != null) {
                androidx.compose.material3.IconButton(onClick = onMoveDown, modifier = Modifier.size(24.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move Down")
                }
            }
            if (onDelete != null) {
                androidx.compose.material3.IconButton(onClick = onDelete, modifier = Modifier.size(24.dp)) {
                    androidx.compose.material3.Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
