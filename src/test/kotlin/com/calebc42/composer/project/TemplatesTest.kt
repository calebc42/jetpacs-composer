// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.project

import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ModelOps
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.org.OrgCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplatesTest {

    @Test
    fun everyTemplateParsesValidatesAndRoundTrips() {
        for (name in Templates.names) {
            val spec = Templates.load(name)
            assertTrue(ModelOps.validate(spec).isEmpty(),
                       "$name: " + ModelOps.validate(spec).joinToString { it.message })
            assertEquals(spec, OrgCodec.parse(OrgCodec.write(spec)),
                         "round-trip drift in template $name")
        }
    }

    @Test
    fun helloWorldCoversEveryViewKind() {
        // The kitchen-sink template (shared verbatim with the ERT suite's
        // elisp/test/fixtures/hello-world.org) must exercise every view
        // kind the model enumerates — a new kind fails here until the
        // canonical fixture grows with it.
        val kinds = Templates.load("hello-world").views.map { it.kind }.toSet()
        for (kind in ViewKind.entries.filterNot { it == ViewKind.UNKNOWN }) {
            assertTrue(kind in kinds, "hello-world is missing a $kind view")
        }
    }

    @Test
    fun wizardBuildsInlineAndExternalApps() {
        val columns = listOf("Item" to ColType.Text, "Qty" to ColType.Number)

        val inline = Templates.build(
            "pantry", "Pantry", "kitchen", "Items", columns = columns)
        assertTrue(ModelOps.validate(inline).isEmpty())
        assertEquals(listOf("Item", "Qty"),
                     ModelOps.firstTable(inline.views.single())!!.header)

        val external = Templates.build("pantry", "Pantry", null, "Items", columns = columns,
                                       backendPath = "/sdcard/org/pantry.org")
        assertTrue(ModelOps.validate(external).isEmpty())
        val view = external.views.single()
        val source = view.source as SourceRef.File
        assertEquals("/sdcard/org/pantry.org", source.file)
        assertEquals("Items", source.heading)
        assertEquals(listOf("Item", "Qty"), view.columns)

        // Both forms survive the codec.
        assertEquals(inline, OrgCodec.parse(OrgCodec.write(inline)))
        assertEquals(external, OrgCodec.parse(OrgCodec.write(external)))
    }
}
