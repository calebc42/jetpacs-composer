// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.project

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec
import com.calebc42.composer.org.OrgCodec

/** The bundled template gallery + the wizard's spec builder. */
object Templates {

    val names = listOf("inventory", "checklist", "contacts")

    fun load(name: String): AppSpec {
        val text = Templates::class.java
            .getResourceAsStream("/templates/$name.org")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("template $name missing from the classpath")
        return OrgCodec.parse(text)
    }

    /**
     * The data-first wizard: columns in, a working one-view app out.
     * [backendPath] null = inline (data in the app document); otherwise
     * the device path of the backing org file, scaffolded on the device
     * from the column names.
     */
    fun build(
        id: String,
        label: String,
        icon: String?,
        viewTitle: String,
        columns: List<Pair<String, ColType>>,
        backendPath: String? = null,
    ): AppSpec {
        require(columns.isNotEmpty()) { "at least one column" }
        val names = columns.map { it.first }
        val types = columns.map { it.second }
        val view = if (backendPath == null) {
            ViewSpec(
                title = viewTitle,
                kind = ViewKind.TABLE,
                order = 10,
                colTypes = types,
                body = listOf(BodyElement.Table(header = names, rows = emptyList())),
            )
        } else {
            ViewSpec(
                title = viewTitle,
                kind = ViewKind.TABLE,
                order = 10,
                source = SourceRef(backendPath, viewTitle),
                colTypes = types,
                columns = names,
            )
        }
        return AppSpec(id = id, label = label, icon = icon, views = listOf(view))
    }
}
