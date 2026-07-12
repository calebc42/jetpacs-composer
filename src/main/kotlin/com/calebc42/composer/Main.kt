// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "jetpacs-composer") {
        MaterialTheme {
            Surface {
                Column(Modifier.padding(24.dp)) {
                    Text("jetpacs-composer", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "The visual editor is under construction. The format " +
                            "(docs/FORMAT.md), the on-device runtime, and the " +
                            "bundle exporter already work — see the README.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
    }
}
