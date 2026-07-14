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
import java.util.ArrayDeque

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
    private var savedSpec = initialSpec
    private val past = ArrayDeque<AppSpec>()
    private val future = ArrayDeque<AppSpec>()
    private var activeCoalesceKey: String? = null
    private var lastEditNanos = 0L

    var spec by mutableStateOf(initialSpec)
        private set
    var file by mutableStateOf(file)
        private set
    var dirty by mutableStateOf(false)
        private set
    var lastError by mutableStateOf<String?>(null)
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /**
     * Which installed pack manifest backs this app's pack pickers, when
     * the user picked one explicitly (else the registry derives it from
     * the document's own pack references — see PackRegistry.selectedPack).
     * Editor-session state; the document records its pack dependency
     * itself via `#+JETPACS_PACK:`.
     */
    var selectedPackId by mutableStateOf<String?>(null)

    /**
     * Apply one edit. Consecutive edits with the same non-null [coalesceKey]
     * within the typing window share one undo snapshot.
     */
    fun update(coalesceKey: String? = null, transform: (AppSpec) -> AppSpec) {
        val before = spec
        runCatching { transform(spec) }
            .onSuccess { after ->
                if (after != before) {
                    val now = System.nanoTime()
                    val coalesces = coalesceKey != null &&
                        coalesceKey == activeCoalesceKey &&
                        now - lastEditNanos <= COALESCE_WINDOW_NANOS
                    if (!coalesces) pushBounded(past, before)
                    future.clear()
                    spec = after
                    dirty = spec != savedSpec
                    activeCoalesceKey = coalesceKey
                    lastEditNanos = now
                    refreshHistoryState()
                }
                lastError = null
            }
            .onFailure { lastError = it.message }
    }

    fun undo(): Boolean {
        if (past.isEmpty()) return false
        pushBounded(future, spec)
        spec = past.removeLast()
        afterHistoryMove()
        return true
    }

    fun redo(): Boolean {
        if (future.isEmpty()) return false
        pushBounded(past, spec)
        spec = future.removeLast()
        afterHistoryMove()
        return true
    }

    private fun afterHistoryMove() {
        activeCoalesceKey = null
        lastEditNanos = 0L
        dirty = spec != savedSpec
        lastError = null
        refreshHistoryState()
    }

    private fun pushBounded(stack: ArrayDeque<AppSpec>, value: AppSpec) {
        stack.addLast(value)
        if (stack.size > HISTORY_LIMIT) stack.removeFirst()
    }

    private fun refreshHistoryState() {
        canUndo = past.isNotEmpty()
        canRedo = future.isNotEmpty()
    }

    fun documentText(): String = OrgCodec.write(spec)

    fun bundleText(): String = BundleExporter.assemble(spec, documentText())

    /** Write the canonical document to [target] (default: current file). */
    fun save(target: File? = null): String? {
        val out = target ?: file ?: return "No file — use Save As"
        return runCatching {
            out.writeText(documentText(), Charsets.UTF_8)
            file = out
            savedSpec = spec
            dirty = false
            activeCoalesceKey = null
            lastEditNanos = 0L
            RecentFiles.remember(out.absolutePath)
            null
        }.getOrElse { it.message }.also { lastError = it }
    }

    /**
     * Write the shippable bundle. Saves first. Lands in EXPORTDIR when given
     * (the Settings "Default Export Path"), else next to the document.
     */
    fun export(exportDir: File? = null): Result<File> {
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
            val out = File(exportDir?.takeIf { it.path.isNotBlank() } ?: doc.parentFile,
                           BundleExporter.bundleFileName(spec))
            out.writeText(BundleExporter.assemble(spec, document), Charsets.UTF_8)
            out
        }.onFailure { lastError = it.message }
    }

    companion object {
        private const val HISTORY_LIMIT = 100
        private const val COALESCE_WINDOW_NANOS = 1_500_000_000L

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
