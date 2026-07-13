// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.org

import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OrgModeKmpShadowParserTest {
    private fun fixture(name: String): String =
        File(System.getProperty("fixtures.dir"), name).readText(Charsets.UTF_8)

    @Test
    fun `legacy codec implements the replaceable format seam`() {
        assertIs<OrgAppCodec>(OrgCodec)
    }

    @Test
    @Ignore("upstream 0.4.1 JVM artifact targets Java 21; enable for the fork's Java 17 release")
    fun `orgmode kmp shadow parser exactly reconstructs canonical app`() {
        val original = fixture("pantry.org")
        val inspection = OrgModeKmpShadowParser.inspect(original)

        assertTrue(inspection.parsed)
        assertTrue(inspection.exactlyReconstructs(original))
        assertEquals(original, inspection.reconstructedText)
        // FORMAT interpretation remains explicitly authoritative here.
        assertEquals("pantry", OrgCodec.parse(original).id)
    }
}
