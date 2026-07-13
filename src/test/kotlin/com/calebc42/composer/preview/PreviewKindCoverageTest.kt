// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.project.Templates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PreviewKindCoverageTest {
    @Test
    fun canonicalHelloWorldProjectsEveryKnownViewKind() {
        val spec = Templates.load("hello-world")
        val projected = PreviewProjection.project(spec)
        val expected = ViewKind.entries.filterNot { it == ViewKind.UNKNOWN }.toSet()

        assertEquals(expected, projected.views.map { it.kind }.toSet())
        assertTrue(projected.views.all { it.renderer != PreviewRendererToken.UNSUPPORTED })
        projected.views.forEach { view ->
            // P0 data projection must also be total for every renderer.
            PreviewDataResolver.resolve(spec, view.route.index)
        }
    }
}
