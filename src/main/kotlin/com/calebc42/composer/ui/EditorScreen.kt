// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.project.ComposerConfig
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
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deploying by remember { mutableStateOf(false) }
    var browsingFiles by remember { mutableStateOf(false) }

    val problems = ModelOps.validate(session.spec)
    val hasErrors = problems.any { it.severity == ModelOps.Severity.Error }

    Column(Modifier.fillMaxSize()) {
        // ── Toolbar ──────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                (session.file?.name ?: "unsaved") + if (session.dirty) " •" else "",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            androidx.compose.material3.IconButton(onClick = onSettings) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            }
            OutlinedButton(onClick = { preview = session.documentText() to "org" }) {
                Text("app.org")
            }
            OutlinedButton(onClick = { preview = session.bundleText() to "elisp" }) {
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
            TextButton(onClick = onClose) { Text("Close") }
        }
        HorizontalDivider()

        // ── Outline | Detail ─────────────────────────────────────────────
        Row(Modifier.weight(1f)) {
            OutlinePane(session, selection, onSelect = { selection = it })
            VerticalDivider()
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                when (val sel = selection) {
                    is Selection.App -> AppForm(session)
                    is Selection.View ->
                        if (sel.index in session.spec.views.indices)
                            ViewForm(session, sel.index)
                        else selection = Selection.App
                }
            }
        }

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

    preview?.let { (text, type) ->
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = {
                TextButton(onClick = { preview = null }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(text))
                }) { Text("Copy to Clipboard") }
            },
            title = { Text(if (type == "org") "app.org Preview" else "Exported Elisp Preview") },
            text = {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.fillMaxWidth().height(420.dp).verticalScroll(rememberScrollState())) {
                        Text(
                            text = highlightSyntax(text, type),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall
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
    Column(
        Modifier.width(260.dp).fillMaxHeight()
            .verticalScroll(rememberScrollState()).padding(8.dp),
    ) {
        OutlineRow(
            label = "App: ${session.spec.id}",
            selected = selection == Selection.App,
            onClick = { onSelect(Selection.App) }
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        session.spec.views.forEachIndexed { i, view ->
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
        Spacer(Modifier.height(12.dp))
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

