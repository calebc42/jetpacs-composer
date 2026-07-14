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
    fun packBackedAppUnionsTheManifestsDependsOverTheBaseline() {
        // A pack app's manifest depends join the always-present engine
        // baseline (never replace it), so its own vulpea-kind views still
        // install their substrate. packdemo has one records view → vulpea.
        val spec = OrgCodec.parse(fixture("packdemo.org"))
        assertEquals(listOf("org-ql", "vulpea", "org", "cl-lib"),
                     Deployer.installList(spec, glasspaneManifest()))
        assertTrue(Deployer.binaryWarnings(spec, glasspaneManifest()).isEmpty())
    }

    @Test
    fun nonPackAppUnionsItsDeclarationsOverTheBaseline() {
        // contacts.org declares nothing; its notes views require vulpea, and
        // the org-ql/vulpea baseline is always present (the shared device
        // snippet must never drop the substrate for a later app).
        val contacts = OrgCodec.parse(fixture("contacts.org"))
        assertEquals(listOf("org-ql", "vulpea"), Deployer.installList(contacts, null))
        // An explicit #+JETPACS_DEPENDS: is unioned on top of the baseline.
        val declared = contacts.copy(depends = listOf("org-super-agenda"))
        assertEquals(listOf("org-ql", "vulpea", "org-super-agenda"),
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
    fun installSnippetIsOneExplicitAppsElLinePerApp() {
        // Phase G: one consenting line in the foundation's apps.el per app —
        // no engine forms (the runtime self-provisions org-ql/vulpea) and no
        // legacy ~/.emacs.d/elisp adopt loop (no wildcard auto-loading of
        // whatever lands in shared storage).
        val s = Deployer.installSnippet("jetpacs-app-pantry.el")
        assertTrue(
            "(add-to-list 'jetpacs-installed-bundles \"jetpacs-app-pantry.el\")" in s)
        assertTrue("jetpacs-config-adopt" in s)     // the BYO-init escape hatch
        assertTrue("package-install" !in s)
        assertTrue("file-expand-wildcards" !in s)
        assertTrue(".emacs.d/elisp" !in s)
        assertEquals(s.count { it == '(' }, s.count { it == ')' })
    }

    @Test
    fun liveDeployTargetsThePhaseGLibDir() {
        // The live path writes where the foundation installs bundles
        // (~/.emacs.d/jetpacs/lib/), never the legacy elisp/ layout — the
        // boot path and the live path must agree on where the app lives.
        // Steps stop at the failed adb forward in this offline test, so the
        // assertion rides the declared step labels via a fake serial.
        val steps = Deployer.liveDeploy("no-such-serial", File("jetpacs-app-x.el"))
        assertTrue(steps.none { ".emacs.d/elisp" in it.label })
    }

    @Test
    fun bootstrapFormsStayBalancedAndIdempotentShaped() {
        val forms = Deployer.depBootstrapForms(
            Deployer.installList(OrgCodec.parse(fixture("packdemo.org")),
                                 glasspaneManifest()))
        assertEquals(forms.count { it == '(' }, forms.count { it == ')' })
        // The install list (baseline ∪ manifest), verbatim, in one dolist.
        assertTrue("(dolist (pkg '(org-ql vulpea org cl-lib))" in forms)
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
