// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.export

import com.calebc42.composer.org.OrgCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleExporterTest {

    private fun fixture(name: String): String =
        File(System.getProperty("fixtures.dir"), name).readText(Charsets.UTF_8)

    @Test
    fun assemblesThePantryBundle() {
        val text = fixture("pantry.org")
        val spec = OrgCodec.parse(text)
        val bundle = BundleExporter.assemble(spec, text)

        assertEquals("jetpacs-app-pantry.el", BundleExporter.bundleFileName(spec))
        assertTrue(bundle.startsWith(
            ";;; jetpacs-app-pantry.el --- Pantry, a Jetpacs CRUD app"))
        assertTrue(";; Jetpacs-App: pantry" in bundle)
        // All three runtime parts ship inside the bundle.
        assertTrue("BEGIN jetpacs-crud.el" in bundle)
        assertTrue("BEGIN jetpacs-crud-vulpea.el" in bundle)
        assertTrue("BEGIN jetpacs-crud-orgapp.el" in bundle)
        assertTrue("(provide 'jetpacs-crud)" in bundle)
        assertTrue("(provide 'jetpacs-crud-vulpea)" in bundle)
        assertTrue("(provide 'jetpacs-crud-orgapp)" in bundle)
        // The install call carries the document verbatim.
        assertTrue("(jetpacs-crud-install \"pantry\" " in bundle)
        assertTrue("#+JETPACS_APP: pantry" in bundle)
        assertTrue(bundle.trimEnd().endsWith(";;; jetpacs-app-pantry.el ends here"))
    }

    /**
     * The two builders must be byte-identical from identical inputs
     * (single source of truth for the editor<->runtime contract).
     * Opt-in because it needs an Emacs-built reference:
     * `gradlew test -PelispBundle=path/to/jetpacs-app-pantry.el`.
     */
    @Test
    fun matchesTheElispReferenceBuilder() {
        val ref = System.getProperty("elisp.bundle").orEmpty()
        if (ref.isEmpty()) return
        val text = fixture("pantry.org")
        val expected = File(ref).readText(Charsets.UTF_8)
        assertEquals(expected, BundleExporter.assemble(OrgCodec.parse(text), text))
    }

    @Test
    fun elispStringEscapesLikePrin1() {
        assertEquals("\"plain\"", BundleExporter.elispString("plain"))
        assertEquals("\"a \\\"quote\\\" and \\\\ backslash\"",
                     BundleExporter.elispString("a \"quote\" and \\ backslash"))
        // Newlines stay literal, exactly as prin1 prints them.
        assertEquals("\"two\nlines\"", BundleExporter.elispString("two\nlines"))
    }
}
