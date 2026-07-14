// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.export

import com.calebc42.composer.model.AppSpec

/**
 * Assembles the shippable single-file bundle: the jetpacs-crud runtime
 * (bundled as resources at build time from elisp/) + the app document +
 * the install call. Mirrors elisp/build-app-bundle.el line for line —
 * the two builders must produce identical bundles from identical
 * inputs; the reference implementation is the elisp one.
 */
object BundleExporter {

    private val RUNTIME_PARTS =
        listOf("jetpacs-crud.el", "jetpacs-crud-vulpea.el", "jetpacs-crud-orgapp.el")

    fun bundleFileName(spec: AppSpec) = "jetpacs-app-${spec.id}.el"

    fun assemble(spec: AppSpec, documentText: String): String = buildString {
        append(";;; jetpacs-app-${spec.id}.el --- ${spec.label ?: spec.id.replaceFirstChar { it.uppercase() }}, a Jetpacs CRUD app -*- lexical-binding: t; -*-\n")
        append(";;\n")
        append(";; Jetpacs-App: ${spec.id}\n")
        append(";; GENERATED FILE -- do not edit by hand; edit the app.org and rebuild.\n")
        append(";; Produced by jetpacs-composer (build-app-bundle.el).\n")
        append(";; Requires the Jetpacs foundation: (require 'jetpacs-core) before loading.\n")
        append(";;\n")
        append(";;; Code:\n\n")
        for (part in RUNTIME_PARTS) {
            append(";;; ==================================================================\n")
            append(";;; BEGIN $part (the jetpacs-composer runtime)\n")
            append(";;; ==================================================================\n\n")
            append(runtimeSource(part))
            append("\n")
        }
        append(";;; ==================================================================\n")
        append(";;; The app document\n")
        append(";;; ==================================================================\n\n")
        append("(jetpacs-crud-install ${elispString(spec.id)} ${elispString(documentText)})\n\n")
        append("(provide 'jetpacs-app-${spec.id})\n")
        append(";;; jetpacs-app-${spec.id}.el ends here\n")
    }

    private fun runtimeSource(name: String): String =
        BundleExporter::class.java.getResourceAsStream("/runtime/$name")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("runtime resource /runtime/$name missing from the classpath")

    /** An elisp read-syntax string literal, matching prin1 (%S). */
    internal fun elispString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
