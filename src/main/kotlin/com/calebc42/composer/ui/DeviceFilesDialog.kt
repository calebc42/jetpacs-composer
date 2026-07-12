package com.calebc42.composer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.calebc42.composer.device.Adb
import com.calebc42.composer.device.DeviceInfo
import com.calebc42.composer.device.FileNode
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceFilesDialog(onDismiss: () -> Unit) {
    var devices by remember { mutableStateOf(emptyList<DeviceInfo>()) }
    var selectedDevice by remember { mutableStateOf<DeviceInfo?>(null) }
    var currentPath by remember { mutableStateOf("/sdcard/") }
    var files by remember { mutableStateOf(emptyList<FileNode>()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        devices = Adb.devices()
        selectedDevice = devices.firstOrNull()
    }

    LaunchedEffect(selectedDevice, currentPath) {
        val dev = selectedDevice ?: return@LaunchedEffect
        loading = true
        files = Adb.ls(dev.serial, currentPath)
        loading = false
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().height(600.dp)
        ) {
            Column {
                // Header
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Device Files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterStart))
                    IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterEnd)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()

                if (devices.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No devices found via ADB")
                    }
                    return@Surface
                }

                // Path header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val parts = currentPath.trimEnd('/').split("/")
                            if (parts.size > 1) {
                                currentPath = parts.dropLast(1).joinToString("/") + "/"
                            }
                        },
                        enabled = currentPath != "/"
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                    Text(currentPath, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                }

                HorizontalDivider()

                // File list
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(files) { file ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (file.isDirectory) {
                                                currentPath += file.name + "/"
                                            }
                                        }
                                        .padding(horizontal = 24.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        if (file.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (file.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(file.name)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()

                // Footer
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = {
                        val dev = selectedDevice ?: return@Button
                        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Push File", java.awt.FileDialog.LOAD)
                        dialog.isVisible = true
                        val selected = dialog.file
                        if (selected != null) {
                            val localPath = dialog.directory + selected
                            val localFile = File(localPath)
                            val targetPath = currentPath + selected
                            Adb.push(dev.serial, localFile, targetPath)
                            // Refresh
                            loading = true
                            files = Adb.ls(dev.serial, currentPath)
                            loading = false
                        }
                    }) {
                        Text("Push file here...")
                    }
                }
            }
        }
    }
}
