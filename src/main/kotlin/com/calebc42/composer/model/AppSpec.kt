// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

import kotlinx.serialization.Serializable

/**
 * The composer-side model of one app document — a 1:1 mirror of the
 * frozen v1 format (docs/FORMAT.md). The org file on disk is the source
 * of truth; this model is what [com.calebc42.composer.org.OrgCodec]
 * reads it into and regenerates it from.
 */
@Serializable
data class AppSpec(
    val id: String,
    val label: String? = null,
    val icon: String? = null,
    val order: Int? = null,
    val views: List<ViewSpec> = emptyList(),
) {
    init {
        require(ID_RE.matches(id)) { "app id must match [a-z][a-z0-9-]*, got \"$id\"" }
    }

    companion object {
        val ID_RE = Regex("[a-z][a-z0-9-]*")
    }
}

@Serializable
enum class ViewKind { TABLE, CHECKLIST, RECORDS }

/** One `%PROP(Label)` token of a records view's `:SCHEMA:`. */
@Serializable
data class SchemaField(val prop: String, val label: String? = null) {
    companion object {
        val SPECIAL = setOf("ITEM", "TODO", "DEADLINE", "SCHEDULED",
                            "PRIORITY", "TAGS")

        /** Upcase names matching org special properties (parser parity). */
        fun of(raw: String, label: String? = null) = SchemaField(
            if (raw.uppercase() in SPECIAL) raw.uppercase() else raw, label)
    }
}

@Serializable
data class ViewSpec(
    val title: String,
    val icon: String? = null,
    val order: Int? = null,
    val kind: ViewKind = ViewKind.TABLE,
    /** null = inline (the data lives in this view's own subtree). */
    val source: SourceRef? = null,
    val colTypes: List<ColType> = emptyList(),
    /** Column names for scaffolding an external table source. */
    val columns: List<String> = emptyList(),
    /** Records views: the `:SCHEMA:` fields. */
    val schema: List<SchemaField> = emptyList(),
    /** Records views: the `:FILTER:` query (org-ql sexp, tokens, or free text). */
    val filter: String? = null,
    /** The view's body content, in order; edited in place, never lost. */
    val body: List<BodyElement> = emptyList(),
) {
    /** The runtime's view slug — must match jetpacs-crud-orgapp--slug. */
    val name: String
        get() = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "view" }
}

@Serializable
data class SourceRef(val file: String, val heading: String? = null)

@Serializable
sealed interface ColType {
    @Serializable object Text : ColType
    @Serializable object Number : ColType
    @Serializable object Date : ColType
    @Serializable object Checkbox : ColType
    @Serializable data class Enum(val options: List<String>) : ColType

    fun toToken(): String = when (this) {
        Text -> "text"
        Number -> "number"
        Date -> "date"
        Checkbox -> "checkbox"
        is Enum -> "enum(${options.joinToString(",")})"
    }
}

/**
 * One block of a view's body. Tables and checklists are structured (the
 * data grid edits them); anything else is an opaque [Raw] passthrough,
 * preserved verbatim so a hand-written document survives the composer.
 */
@Serializable
sealed interface BodyElement {
    @Serializable
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    ) : BodyElement

    @Serializable
    data class Checklist(val items: List<ChecklistItem>) : BodyElement

    @Serializable
    data class Raw(val text: String) : BodyElement
}

@Serializable
data class ChecklistItem(val state: String, val text: String) {
    init {
        require(state in listOf(" ", "X", "x", "-")) { "bad checkbox state \"$state\"" }
    }
}
