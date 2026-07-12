// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

val CommonIcons: List<Pair<String, ImageVector>> = listOf(
    "home" to Icons.Default.Home,
    "settings" to Icons.Default.Settings,
    "person" to Icons.Default.Person,
    "info" to Icons.Default.Info,
    "search" to Icons.Default.Search,
    "favorite" to Icons.Default.Favorite,
    "star" to Icons.Default.Star,
    "done" to Icons.Default.Done,
    "add" to Icons.Default.Add,
    "edit" to Icons.Default.Edit,
    "delete" to Icons.Default.Delete,
    "list" to Icons.Default.List,
    "shopping_cart" to Icons.Default.ShoppingCart,
    "mail" to Icons.Default.Email,
    "phone" to Icons.Default.Phone,
    "place" to Icons.Default.Place,
    "build" to Icons.Default.Build,
    "check_circle" to Icons.Default.CheckCircle,
    "warning" to Icons.Default.Warning,
    "lock" to Icons.Default.Lock,
    "calendar_today" to Icons.Default.DateRange,
    "camera" to Icons.Default.PhotoCamera,
    "kitchen" to Icons.Default.Kitchen,
    "table_chart" to Icons.Default.TableChart,
    "checklist" to Icons.Default.Checklist,
    "palette" to Icons.Default.Palette,
)

@Composable
fun IconPicker(
    onIconSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    
    val filteredIcons = remember(searchQuery) {
        if (searchQuery.isBlank()) CommonIcons
        else CommonIcons.filter { it.first.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select an Icon") },
        text = {
            Column(Modifier.fillMaxWidth().height(400.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search icons") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                )
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 64.dp),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredIcons) { (name, vector) ->
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(64.dp)
                                .clickable { onIconSelected(name) }
                                .padding(8.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = vector,
                                    contentDescription = name,
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
