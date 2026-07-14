// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calebc42.composer.device.Adb
import com.calebc42.composer.device.DeviceInfo
import com.calebc42.composer.device.Deployer
import com.calebc42.composer.export.BundleExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeployDialog(session: EditorSession, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceInfo>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var companion by remember { mutableStateOf<Boolean?>(null) }
    var log by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var showSnippet by remember { mutableStateOf(false) }

    // Termux-binary warnings for THIS app (S4.4). MELPA engines no longer
    // gate the deploy: the bundle runtime installs its own engine pair on
    // the device (first load, or the degraded view's Install button).
    val binaryWarnings = Deployer.binaryWarnings(session.spec, session.exportManifest())
    val bundleName = BundleExporter.bundleFileName(session.spec)

    fun refresh() = scope.launch {
        withContext(Dispatchers.IO) {
            val found = Adb.devices()
            devices = found
            if (selected == null) selected = found.firstOrNull { it.ready }?.serial
            companion = selected?.let { Adb.companionInstalled(it) }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    fun deploy(live: Boolean) {
        val serial = selected ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                session.export().fold(
                    onFailure = { "Export failed: ${it.message}" },
                    onSuccess = { bundle ->
                        val steps = if (live) Deployer.liveDeploy(serial, bundle)
                        else Deployer.stagingDeploy(serial, bundle)
                        steps.joinToString("\n") { s ->
                            val mark = if (s.result.ok) "✓" else "✗"
                            "$mark ${s.label}" +
                                (if (!s.result.ok)
                                    "\n   ${s.result.stderr.ifBlank { s.result.stdout }.trim()}"
                                 else "")
                        } + if (!live && steps.all { it.result.ok })
                            "\nStaged. Restart Emacs on the device to adopt it " +
                                "(the app must be listed in apps.el once — see " +
                                "Install snippet). Engines install themselves " +
                                "on first load."
                        else ""
                    },
                )
            }
            log = result
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Deploy to device") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (Adb.executable == null) {
                    Text("adb was not found on PATH or in the Android SDK. " +
                             "Install platform-tools or add adb to PATH.",
                         color = MaterialTheme.colorScheme.error)
                    return@Column
                }
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Devices:", style = MaterialTheme.typography.titleSmall)
                    OutlinedButton(onClick = { refresh() }) { Text("Refresh") }
                }
                if (devices.isEmpty()) Text("None connected.")
                devices.forEach { d ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected == d.serial, onClick = {
                            selected = d.serial
                            companion = null
                            refresh()
                        }, enabled = d.ready)
                        Text("${d.model ?: d.serial}  (${d.serial}, ${d.state})" +
                                 when {
                                     selected != d.serial -> ""
                                     companion == true -> "  · companion ✓"
                                     companion == false -> "  · companion NOT installed"
                                     else -> ""
                                 })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { deploy(live = false) },
                                   enabled = selected != null && !busy) {
                        Text("Stage (adb push + restart)")
                    }
                    OutlinedButton(onClick = { deploy(live = true) },
                                   enabled = selected != null && !busy) {
                        Text("Live (sshd + emacsclient)")
                    }
                    TextButton(onClick = { showSnippet = !showSnippet }) {
                        Text("Install snippet")
                    }
                }
                Text(
                    "Live needs `sshd` running in Termux and an Emacs server " +
                        "on the device; staging only needs adb.",
                    style = MaterialTheme.typography.bodySmall,
                )
                binaryWarnings.forEach { warning ->
                    Text("⚠ $warning",
                         style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
                if (showSnippet) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text("Add this app once to ~/.emacs.d/jetpacs/apps.el — " +
                             "later re-deploys need no edits, and the runtime " +
                             "installs its own engines (org-ql, vulpea):",
                         style = MaterialTheme.typography.bodySmall)
                    Text(Deployer.installSnippet(bundleName),
                         fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall)
                }
                if (log.isNotBlank()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(log, fontFamily = FontFamily.Monospace,
                         style = MaterialTheme.typography.bodySmall)
                }
            }
        },
    )
}
