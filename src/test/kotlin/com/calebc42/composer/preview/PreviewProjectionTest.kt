// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

import com.calebc42.composer.model.ActionDef
import com.calebc42.composer.model.AggregateOp
import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.DashboardMetric
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewNav
import com.calebc42.composer.model.ViewSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PreviewProjectionTest {

    @Test
    fun projectsRuntimeDestinationOrderAndGrouping() {
        val spec = AppSpec(
            id = "nav",
            views = listOf(
                ViewSpec("Later drawer", order = 30, nav = ViewNav.DRAWER),
                ViewSpec("Group second", order = 50, group = "Work"),
                ViewSpec("Middle tab", order = 20),
                ViewSpec("Group first by order", order = 5, group = "Work"),
                ViewSpec("Early drawer", order = 10, nav = ViewNav.DRAWER),
            ),
        )

        val app = PreviewProjection.project(spec)

        assertEquals(
            listOf("Work", "Early drawer", "Middle tab", "Later drawer"),
            app.destinations.map { it.label },
        )
        assertEquals(listOf("Work", "Middle tab"), app.tabs.map { it.label })
        assertEquals(listOf("Early drawer", "Later drawer"), app.drawer.map { it.label })
        assertEquals(listOf("Work"), app.groups.map { it.label })

        val group = app.destinations.first()
        assertTrue(group.isGroup)
        assertEquals(PreviewPlacement.TAB, group.placement)
        assertEquals(5, group.order)
        // Runtime group members retain document order; their own ORDER only
        // selects the shell destination's minimum order.
        assertEquals(listOf(1, 3), group.members.map { it.index })
        assertEquals("group-second", group.members.first().slug)
    }

    @Test
    fun projectsAppDefaultsAndViewMetadataWithoutSerialization() {
        val view = ViewSpec(
            title = "Revenue",
            kind = ViewKind.DASHBOARD,
            source = SourceRef.File("records.org", "Sales"),
            schema = listOf(
                SchemaField("ITEM", "Account"),
                SchemaField("AMOUNT", "Revenue"),
                SchemaField("DEADLINE"),
            ),
            colTypes = listOf(ColType.Text, ColType.Number),
            filter = "TODO=NEXT",
            groupBy = "ITEM",
            metrics = listOf(
                DashboardMetric(AggregateOp.COUNT),
                DashboardMetric(AggregateOp.SUM, "AMOUNT"),
            ),
            actions = listOf(
                ActionDef.SetTodo("DONE"),
                ActionDef.SetPriority("A"),
            ),
        )

        val app = PreviewProjection.project(AppSpec(id = "sales-app", views = listOf(view)))
        val preview = app.views.single()

        assertEquals("Sales-App", app.label)
        assertEquals("apps", app.icon)
        assertEquals(100, app.order)
        assertEquals("bar_chart", preview.icon)
        assertEquals(10, preview.order)
        assertEquals(PreviewRendererToken.DASHBOARD, preview.renderer)
        assertEquals("TODO=NEXT", preview.filter)
        assertEquals("ITEM", preview.groupBy)
        assertEquals(
            listOf("Account", "Revenue", "DEADLINE"),
            preview.fields.map { it.label },
        )
        assertEquals(
            listOf(ColType.Text, ColType.Number, ColType.Date),
            preview.fields.map { it.type },
        )
        assertEquals(listOf("Count", "Sum AMOUNT"), preview.metrics.map { it.label })
        assertEquals(
            listOf("Set TODO to DONE", "Set priority A"),
            preview.actions.map { it.label },
        )
        assertTrue(preview.actions.all { it.previewOnly })
        assertEquals(
            PreviewSource.File("records.org", "Sales"),
            preview.source,
        )
    }

    @Test
    fun tableFieldsUseInlineHeaderOrExternalColumns() {
        val inline = ViewSpec(
            title = "Inline",
            kind = ViewKind.TABLE,
            colTypes = listOf(ColType.Text, ColType.Checkbox),
            body = listOf(BodyElement.Table(
                header = listOf("Task", "Done"),
                rows = listOf(listOf("Ship", "[ ]")),
            )),
        )
        val external = ViewSpec(
            title = "External",
            kind = ViewKind.TABLE,
            source = SourceRef.File("external.org"),
            columns = listOf("When", "Value"),
            colTypes = listOf(ColType.Date, ColType.Number),
        )

        val app = PreviewProjection.project(
            AppSpec(id = "tables", views = listOf(inline, external)),
        )

        assertEquals(listOf("Task", "Done"), app.views[0].fields.map { it.key })
        assertEquals(listOf("When", "Value"), app.views[1].fields.map { it.key })
        assertIs<PreviewSource.Inline>(app.views[0].source)
        assertEquals(PreviewSource.File("external.org", null), app.views[1].source)
    }

    @Test
    fun validationAndUnknownVocabularyBecomeNoticesInsteadOfCrashes() {
        val view = ViewSpec(
            title = "???",
            kind = ViewKind.UNKNOWN,
            schema = listOf(SchemaField("Mystery")),
            colTypes = listOf(ColType.Unknown("future(widget)")),
            actions = listOf(ActionDef.Unknown("teleport")),
            nav = ViewNav.DRAWER,
            group = "Only member",
        )

        val app = PreviewProjection.project(AppSpec(id = "partial", views = listOf(view)))
        val projected = app.views.single()

        assertEquals(PreviewRendererToken.UNSUPPORTED, projected.renderer)
        assertEquals(ColType.Unknown("future(widget)"), projected.fields.single().type)
        assertEquals("future(widget)", projected.notices.first {
            it.code == "unsupported-coltype"
        }.message.substringAfter('"').substringBeforeLast('"'))
        assertTrue(projected.notices.any { it.code == "unsupported-kind" })
        assertTrue(projected.notices.any { it.code == "unsupported-action" })
        assertTrue(projected.notices.any {
            it.code == "validation" && it.severity == PreviewNoticeSeverity.WARNING
        })
        // GROUP wins over NAV just as it does at runtime.
        assertEquals(PreviewPlacement.TAB, projected.placement)
        assertEquals(PreviewPlacement.TAB, app.destinations.single().placement)
        assertTrue(app.notices.containsAll(projected.notices))
    }

    @Test
    fun emptyAndMalformedSpecsRemainRenderable() {
        val empty = PreviewProjection.project(AppSpec(id = "empty"))
        assertTrue(empty.views.isEmpty())
        assertTrue(empty.destinations.isEmpty())
        assertTrue(empty.notices.any {
            it.message.contains("at least one view") &&
                it.severity == PreviewNoticeSeverity.ERROR
        })

        val malformed = PreviewProjection.project(AppSpec(
            id = "broken",
            views = listOf(ViewSpec(
                title = "",
                kind = ViewKind.BOARD,
                schema = emptyList(),
                groupBy = "Missing",
            )),
        ))
        assertEquals("view", malformed.views.single().route.slug)
        assertTrue(malformed.views.single().notices.any {
            it.severity == PreviewNoticeSeverity.ERROR
        })
    }

    @Test
    fun everyViewKindHasAnExplicitProjectionToken() {
        val spec = AppSpec(
            id = "coverage",
            views = ViewKind.entries.mapIndexed { index, kind ->
                ViewSpec(
                    title = "${kind.name} $index",
                    kind = kind,
                    schema = if (kind in recordKinds) listOf(SchemaField("ITEM")) else emptyList(),
                )
            },
        )

        val app = PreviewProjection.project(spec)

        assertEquals(ViewKind.entries.size, app.views.size)
        assertEquals(
            ViewKind.entries.map(PreviewRendererToken::from),
            app.views.map { it.renderer },
        )
        ViewKind.entries.forEach { kind ->
            assertNotNull(PreviewRendererToken.from(kind))
        }
    }

    @Test
    fun routeReconciliationPrefersSlugThenFallsBackToDocumentIndex() {
        val original = PreviewProjection.project(AppSpec(
            id = "routes",
            views = listOf(ViewSpec("One"), ViewSpec("Two")),
        ))
        val selected = original.views[1].route

        val reordered = PreviewProjection.project(AppSpec(
            id = "routes",
            views = listOf(ViewSpec("Two"), ViewSpec("One")),
        ))
        assertEquals(0, PreviewProjection.reconcileRoute(reordered, selected)?.index)

        val renamed = PreviewProjection.project(AppSpec(
            id = "routes",
            views = listOf(ViewSpec("One"), ViewSpec("Two being edited")),
        ))
        assertEquals(
            PreviewViewRoute("two-being-edited", 1),
            PreviewProjection.reconcileRoute(renamed, selected),
        )

        val shrunk = PreviewProjection.project(AppSpec(
            id = "routes",
            views = listOf(ViewSpec("Only")),
        ))
        assertEquals(shrunk.views.first().route, PreviewProjection.reconcileRoute(shrunk, selected))
        assertEquals(shrunk.views.first().route, PreviewProjection.reconcileRoute(shrunk, null))
        assertEquals(null, PreviewProjection.reconcileRoute(
            PreviewProjection.project(AppSpec(id = "routes")), selected,
        ))
    }

    private companion object {
        val recordKinds = setOf(
            ViewKind.RECORDS,
            ViewKind.NOTES,
            ViewKind.BOARD,
            ViewKind.CALENDAR,
            ViewKind.GALLERY,
            ViewKind.TREE,
            ViewKind.DASHBOARD,
            ViewKind.GANTT,
            ViewKind.UNKNOWN,
        )
    }
}
