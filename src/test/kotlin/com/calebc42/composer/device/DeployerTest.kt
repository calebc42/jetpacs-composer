// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.device

import com.calebc42.composer.model.PackManifest
import com.calebc42.composer.org.OrgCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * S4.4: the dependency bootstrap is manifest-driven. Exercised over the
 * real committed glasspane manifest and the shared org fixtures.
 */
class DeployerTest {

    private fun fixture(name: String): String =
        File(System.getProperty("fixtures.dir"), name).readText(Charsets.UTF_8)

    private fun glasspaneManifest(): PackManifest =
        PackManifest.parse(fixture("glasspane-pack.json"))

    @Test
    fun packBackedAppInstallsTheManifestsDepends() {
        val spec = OrgCodec.parse(fixture("packdemo.org"))
        assertEquals(listOf("org", "org-ql", "vulpea", "cl-lib"),
                     Deployer.installList(spec, glasspaneManifest()))
        assertTrue(Deployer.binaryWarnings(spec, glasspaneManifest()).isEmpty())
    }

    @Test
    fun nonPackAppFallsBackToItsOwnDeclarations() {
        // contacts.org declares nothing; its notes views require vulpea.
        val contacts = OrgCodec.parse(fixture("contacts.org"))
        assertEquals(listOf("vulpea"), Deployer.installList(contacts, null))
        // An explicit #+JETPACS_DEPENDS: is unioned with the derived set.
        val declared = contacts.copy(depends = listOf("org-ql"))
        assertEquals(listOf("org-ql", "vulpea"),
                     Deployer.installList(declared, null))
    }

    @Test
    fun binaryDepsAreWarnedNeverInstalled() {
        val spec = OrgCodec.parse(fixture("packdemo.org"))
        val withBinary = glasspaneManifest().copy(
            depends = glasspaneManifest().depends +
                PackManifest.Depend("ripgrep") + PackManifest.Depend("fd"))
        val installed = Deployer.installList(spec, withBinary)
        assertTrue("ripgrep" !in installed && "fd" !in installed)
        val warnings = Deployer.binaryWarnings(spec, withBinary)
        assertEquals(2, warnings.size)
        assertTrue(warnings.all { "Termux" in it })
    }

    @Test
    fun bootstrapFormsStayBalancedAndIdempotentShaped() {
        val forms = Deployer.depBootstrapForms(
            Deployer.installList(OrgCodec.parse(fixture("packdemo.org")),
                                 glasspaneManifest()))
        assertEquals(forms.count { it == '(' }, forms.count { it == ')' })
        // The manifest's install list, verbatim, in one dolist.
        assertTrue("(dolist (pkg '(org org-ql vulpea cl-lib))" in forms)
        // The offline-retry + idempotence shape survives parameterization.
        assertTrue("condition-case" in forms)
        assertTrue("package-installed-p" in forms)
        assertTrue("install deferred" in forms)
        // The autosync wiring (vault + installed-apps dir) stays.
        assertTrue("vulpea-db-autosync-mode" in forms)
        assertTrue("jetpacs-crud/" in forms)
        assertTrue(".vulpea-scanned" in forms)
    }
}
