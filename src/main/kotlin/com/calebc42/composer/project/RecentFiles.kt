// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.project

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ComposerConfig(
    val recent: List<String> = emptyList(),
)

/** %APPDATA%\jetpacs-composer\config.json (XDG-ish fallback elsewhere). */
object RecentFiles {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val configFile: File by lazy {
        val base = System.getenv("APPDATA")?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
        File(base, "jetpacs-composer/config.json")
    }

    fun load(): ComposerConfig = runCatching {
        json.decodeFromString<ComposerConfig>(configFile.readText(Charsets.UTF_8))
    }.getOrDefault(ComposerConfig())

    fun remember(path: String) {
        val config = load()
        val recent = (listOf(path) + config.recent.filter { it != path }).take(10)
        runCatching {
            configFile.parentFile.mkdirs()
            configFile.writeText(json.encodeToString(config.copy(recent = recent)),
                                 Charsets.UTF_8)
        }
    }
}
