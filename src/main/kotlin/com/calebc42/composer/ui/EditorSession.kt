// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.calebc42.composer.export.BundleExporter
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.org.OrgCodec
import com.calebc42.composer.project.RecentFiles
import java.io.File

/**
 * One open app document. The file on disk is the source of truth: every
 * save regenerates the canonical org form of the current model. (The
 * composer owns its documents — hand-edited exotica should be opened,
 * inspected via the raw preview, and only saved knowingly.)
 */
class EditorSession private constructor(
    initialSpec: AppSpec,
    file: File?,
) {
    var spec by mutableStateOf(initialSpec)
        private set
    var file by mutableStateOf(file)
        private set
    var dirty by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)

    fun update(transform: (AppSpec) -> AppSpec) {
        runCatching { transform(spec) }
            .onSuccess { spec = it; dirty = true; lastError = null }
            .onFailure { lastError = it.message }
    }

    fun documentText(): String = OrgCodec.write(spec)

    fun bundleText(): String = BundleExporter.assemble(spec, documentText())

    /** Write the canonical document to [target] (default: current file). */
    fun save(target: File? = null): String? {
        val out = target ?: file ?: return "No file — use Save As"
        return runCatching {
            out.writeText(documentText(), Charsets.UTF_8)
            file = out
            dirty = false
            RecentFiles.remember(out.absolutePath)
            null
        }.getOrElse { it.message }.also { lastError = it }
    }

    /** Write the shippable bundle next to the document. Saves first. */
    fun export(): Result<File> {
        val errors = ModelOps.validate(spec)
            .filter { it.severity == ModelOps.Severity.Error }
        if (errors.isNotEmpty()) {
            val message = buildString {
                append("Bundle export blocked by ${errors.size} error")
                if (errors.size != 1) append('s')
                append(": ")
                append(errors.joinToString("; ") { problem ->
                    val view = problem.viewIndex
                        ?.let { spec.views.getOrNull(it)?.title }
                    if (view == null) problem.message
                    else "$view: ${problem.message}"
                })
            }
            lastError = message
            return Result.failure(IllegalStateException(message))
        }
        val document = runCatching {
            documentText().also { OrgCodec.parse(it) }
        }.getOrElse { cause ->
            val message = "Bundle export blocked because the generated app.org " +
                "is invalid: ${cause.message ?: cause::class.simpleName}"
            lastError = message
            return Result.failure(IllegalStateException(message, cause))
        }
        save()?.let { return Result.failure(Exception(it)) }
        val doc = file!!
        return runCatching {
            val out = File(doc.parentFile, BundleExporter.bundleFileName(spec))
            out.writeText(BundleExporter.assemble(spec, document), Charsets.UTF_8)
            out
        }.onFailure { lastError = it.message }
    }

    companion object {
        fun open(file: File): Result<EditorSession> = runCatching {
            val spec = OrgCodec.parse(file.readText(Charsets.UTF_8))
            RecentFiles.remember(file.absolutePath)
            EditorSession(spec, file)
        }

        fun new(id: String, label: String): EditorSession =
            EditorSession(AppSpec(id = id, label = label), file = null)

        /** A session over a wizard- or template-built spec (unsaved). */
        fun fromSpec(spec: AppSpec): EditorSession = EditorSession(spec, file = null)
    }
}
