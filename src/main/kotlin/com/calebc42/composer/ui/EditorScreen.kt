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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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

/** What the detail pane is editing. */
sealed interface Selection {
    data object App : Selection
    data class View(val index: Int) : Selection
}

@Composable
fun EditorScreen(session: EditorSession, onClose: () -> Unit) {
    var selection by remember { mutableStateOf<Selection>(Selection.App) }
    var preview by remember { mutableStateOf<String?>(null) }
    var deploying by remember { mutableStateOf(false) }

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
            OutlinedButton(onClick = { preview = session.documentText() }) {
                Text("app.org")
            }
            OutlinedButton(onClick = { preview = session.bundleText() }) {
                Text("Exported elisp")
            }
            Button(onClick = {
                if (session.file == null)
                    pickSaveFile("Save app document", "${session.spec.id}.org")
                        ?.let { session.save(it) }
                else session.save()
            }) { Text("Save") }
            Button(onClick = {
                if (session.file == null)
                    pickSaveFile("Save app document", "${session.spec.id}.org")
                        ?.let { session.save(it) }
                session.export()
            }, enabled = session.file != null || true) { Text("Export bundle") }
            Button(onClick = { deploying = true }) { Text("Deploy…") }
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
        val problems = ModelOps.validate(session.spec)
        val error = session.lastError
        if (problems.isNotEmpty() || error != null) {
            HorizontalDivider()
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                problems.forEach { p ->
                    Text(
                        (p.viewIndex?.let { i ->
                            "${session.spec.views.getOrNull(i)?.title}: "
                        } ?: "") + p.message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.clickable {
                            p.viewIndex?.let { selection = Selection.View(it) }
                        },
                    )
                }
            }
        }
    }

    if (deploying) DeployDialog(session, onDismiss = { deploying = false })

    preview?.let { text ->
        AlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = {
                TextButton(onClick = { preview = null }) { Text("Close") }
            },
            title = { Text("Preview") },
            text = {
                Column(Modifier.height(420.dp).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall)
                }
            },
        )
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
        OutlineRow("App: ${session.spec.id}", selection == Selection.App) {
            onSelect(Selection.App)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        session.spec.views.forEachIndexed { i, view ->
            OutlineRow(
                "${if (view.kind == ViewKind.CHECKLIST) "☑" else "▦"} ${view.title}",
                selection == Selection.View(i),
            ) { onSelect(Selection.View(i)) }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedButton(onClick = {
                session.update { ModelOps.addView(it, "New table", ViewKind.TABLE) }
                onSelect(Selection.View(session.spec.views.size))
            }) { Text("+ Table") }
            OutlinedButton(onClick = {
                session.update { ModelOps.addView(it, "New checklist", ViewKind.CHECKLIST) }
                onSelect(Selection.View(session.spec.views.size))
            }) { Text("+ Checklist") }
        }
        (selection as? Selection.View)?.let { sel ->
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    session.update { ModelOps.moveView(it, sel.index, -1) }
                    if (sel.index > 0) onSelect(Selection.View(sel.index - 1))
                }) { Text("↑") }
                OutlinedButton(onClick = {
                    session.update { ModelOps.moveView(it, sel.index, +1) }
                    if (sel.index < session.spec.views.size - 1)
                        onSelect(Selection.View(sel.index + 1))
                }) { Text("↓") }
                OutlinedButton(onClick = {
                    session.update { ModelOps.removeView(it, sel.index) }
                    onSelect(Selection.App)
                }) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun OutlineRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth()
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    )
}
