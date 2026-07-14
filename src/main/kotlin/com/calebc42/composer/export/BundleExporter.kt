// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.export

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.PackManifest
import com.calebc42.composer.model.usesPackFeatures

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

    fun assemble(spec: AppSpec, documentText: String,
                 pack: PackManifest? = null): String = buildString {
        // Fail closed: a pack-backed app exports only against its locally
        // installed manifest, and the manifest must be the declared pack —
        // the registration the bundle carries is trusted code derived from
        // it, never from app data (SPEC §5).
        if (spec.usesPackFeatures()) {
            checkNotNull(pack) {
                "this app uses pack references — exporting needs the " +
                    "installed pack manifest (Settings → pack manifest directory)"
            }
            spec.pack?.let {
                check(it.packId == pack.pack_id) {
                    "declared pack \"${it.packId}\" but the selected manifest " +
                        "is \"${pack.pack_id}\""
                }
            }
        }
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
        if (spec.usesPackFeatures() && pack != null) {
            append(";;; ==================================================================\n")
            append(";;; The pack manifest (trusted registration; exported against it)\n")
            append(";;; ==================================================================\n\n")
            append(packRegistration(pack))
            append("\n")
        }
        append(";;; ==================================================================\n")
        append(";;; The app document\n")
        append(";;; ==================================================================\n\n")
        append("(jetpacs-crud-install ${elispString(spec.id)} ${elispString(documentText)})\n\n")
        append("(provide 'jetpacs-app-${spec.id})\n")
        append(";;; jetpacs-app-${spec.id}.el ends here\n")
    }

    /**
     * The trusted `jetpacs-crud-pack-register` form — must stay
     * byte-identical to jetpacs-crud-bundle--pack-registration (the elisp
     * reference builder).
     */
    internal fun packRegistration(pack: PackManifest): String = buildString {
        append("(jetpacs-crud-pack-register ${elispString(pack.pack_id)}")
        pack.feature?.let { append(" :feature '$it") }
        append(" :version ${elispString(pack.pack_version)}")
        if (pack.sources.isNotEmpty())
            append(" :sources '(" +
                pack.sources.joinToString(" ") { elispString(it.name) } + ")")
        if (pack.actions.isNotEmpty())
            append(" :actions '(" +
                pack.actions.joinToString(" ") { elispString(it.action) } + ")")
        append(")\n")
    }

    private fun runtimeSource(name: String): String =
        BundleExporter::class.java.getResourceAsStream("/runtime/$name")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("runtime resource /runtime/$name missing from the classpath")

    /** An elisp read-syntax string literal, matching prin1 (%S). */
    internal fun elispString(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
