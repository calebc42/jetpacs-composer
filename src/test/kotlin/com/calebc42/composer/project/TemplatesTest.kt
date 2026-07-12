// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.project

import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.ModelOps
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
    fun wizardBuildsInlineAndExternalApps() {
        val columns = listOf("Item" to ColType.Text, "Qty" to ColType.Number)

        val inline = Templates.build("pantry", "Pantry", "kitchen", "Items", columns)
        assertTrue(ModelOps.validate(inline).isEmpty())
        assertEquals(listOf("Item", "Qty"),
                     ModelOps.firstTable(inline.views.single())!!.header)

        val external = Templates.build("pantry", "Pantry", null, "Items", columns,
                                       backendPath = "/sdcard/org/pantry.org")
        assertTrue(ModelOps.validate(external).isEmpty())
        val view = external.views.single()
        assertEquals("/sdcard/org/pantry.org", view.source?.file)
        assertEquals("Items", view.source?.heading)
        assertEquals(listOf("Item", "Qty"), view.columns)

        // Both forms survive the codec.
        assertEquals(inline, OrgCodec.parse(OrgCodec.write(inline)))
        assertEquals(external, OrgCodec.parse(OrgCodec.write(external)))
    }
}
