// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

import kotlinx.serialization.Serializable

/**
 * The composer-side model of one app document — a 1:1 mirror of the
 * current v2 format (docs/FORMAT.md). The org file on disk is the source
 * of truth; this model is what [com.calebc42.composer.org.OrgCodec]
 * reads it into and regenerates it from.
 */
@Serializable
data class AppSpec(
    val id: String,
    val label: String? = null,
    val icon: String? = null,
    val order: Int? = null,
    /** Custom TODO keyword sequence. Keywords before the `|` are active; after are done states. */
    val todoSequence: List<TodoKeyword> = emptyList(),
    /** Tag vocabulary for the file (emitted as #+TAGS:). */
    val tags: List<String> = emptyList(),
    /** Append-only quick-capture destination (`#+JETPACS_INBOX:`). */
    val inbox: String? = null,
    val views: List<ViewSpec> = emptyList(),
) {
    init {
        require(ID_RE.matches(id)) { "app id must match [a-z][a-z0-9-]*, got \"$id\"" }
    }

    companion object {
        val ID_RE = Regex("[a-z][a-z0-9-]*")
    }
}

/** One keyword in a `#+TODO:` sequence. */
@Serializable
data class TodoKeyword(val keyword: String, val isDone: Boolean)

@Serializable
enum class ViewKind { TABLE, CHECKLIST, RECORDS, NOTES, BOARD, CALENDAR, GALLERY, TREE, UNKNOWN }

/**
 * Where a view lives in the app chrome (`:NAV:`). [TAB] is a bottom-bar
 * destination (the default); [DRAWER] routes it into the ☰ navigation
 * drawer instead. A grouped view ([ViewSpec.group]) ignores this — it
 * belongs to its group's destination.
 */
@Serializable
enum class ViewNav { TAB, DRAWER }

/** Metadata about an org built-in property: its natural column type and a description. */
data class OrgBuiltin(val defaultType: ColType, val description: String)

/** One `%PROP(Label)` token of a records view's `:SCHEMA:`. */
@Serializable
data class SchemaField(val prop: String, val label: String? = null) {
    companion object {
        /** Built-in org properties with their natural types and human descriptions. */
        val ORG_BUILTINS = mapOf(
            "ITEM"      to OrgBuiltin(ColType.Text,     "Heading text"),
            "TODO"      to OrgBuiltin(ColType.Enum(listOf("TODO","NEXT","DOING","WAITING","DONE","CANCELLED")), "TODO keyword"),
            "DEADLINE"  to OrgBuiltin(ColType.Date,     "Deadline timestamp"),
            "SCHEDULED" to OrgBuiltin(ColType.Date,     "Scheduled timestamp"),
            "PRIORITY"  to OrgBuiltin(ColType.Enum(listOf("A","B","C")), "Priority letter"),
            "TAGS"      to OrgBuiltin(ColType.Text,     "Headline tags"),
            "EFFORT"    to OrgBuiltin(ColType.Text,     "Effort estimate (HH:MM)"),
            "CATEGORY"  to OrgBuiltin(ColType.Text,     "Category (inherited)"),
        )

        /** Names that the parser upcases automatically (org special properties). */
        val SPECIAL = ORG_BUILTINS.keys

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
    /** Board views: which schema field to group columns by (e.g. "TODO"). */
    val groupBy: String? = null,
    /** Calendar views: which schema field provides the date (e.g. "DEADLINE"). */
    val dateField: String? = null,
    /** Gallery views: which schema field provides the image path. */
    val imageField: String? = null,
    /** Optional durable reminder derived from one org date field. */
    val reminder: DateReminderRule? = null,
    /** Per-view actions (org-native operations shown as buttons/swipe actions). */
    val actions: List<ActionDef> = emptyList(),
    /** Where the view lives in the chrome (`:NAV:`); ignored when [group] is set. */
    val nav: ViewNav = ViewNav.TAB,
    /**
     * `:GROUP:` — a destination name. Views sharing one collapse into a single
     * tabbed bottom destination. null = a standalone view (placed per [nav]).
     */
    val group: String? = null,
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
data class DateReminderRule(
    val dateField: String,
    val relativeDays: Int = 0,
)

/**
 * Where a view's records come from. Mirrors jetpacs-crud-orgapp--parse-source:
 * a trailing-slash `dir/` SOURCE is a note vault ([Dir]); anything else is a
 * single file, optionally scoped to a `*Heading` ([File]).
 */
@Serializable
sealed interface SourceRef {
    /** A single org file, optionally narrowed to a `*Heading` subtree. */
    @Serializable
    data class File(val file: String, val heading: String? = null) : SourceRef

    /** A note vault: a `dir/` SOURCE, one note file per record. */
    @Serializable
    data class Dir(val dir: String) : SourceRef
}

@Serializable
sealed interface ColType {
    @Serializable object Text : ColType
    @Serializable object Number : ColType
    @Serializable object Date : ColType
    @Serializable object Checkbox : ColType
    @Serializable data class Enum(val options: List<String>) : ColType
    @Serializable data class Ref(
        val targetView: String,
        val displayField: String? = null,
    ) : ColType
    @Serializable data class Unknown(val token: String) : ColType

    fun toToken(): String = when (this) {
        Text -> "text"
        Number -> "number"
        Date -> "date"
        Checkbox -> "checkbox"
        is Enum -> "enum(${options.joinToString(",")})"
        is Ref -> "ref($targetView${displayField?.let { ",$it" }.orEmpty()})"
        is Unknown -> token
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

/**
 * An action that can be performed on a record within a view.
 * Each maps to a native org-mode / Emacs operation.
 */
@Serializable
sealed interface ActionDef {
    /** Change a record's TODO keyword. */
    @Serializable data class SetTodo(val keyword: String) : ActionDef
    /** Set a SCHEDULED timestamp. */
    @Serializable data class Schedule(val prompt: Boolean = true) : ActionDef
    /** Set a DEADLINE timestamp. */
    @Serializable data class SetDeadline(val prompt: Boolean = true) : ActionDef
    /** Set headline tags. */
    @Serializable data class SetTags(val tags: List<String> = emptyList()) : ActionDef
    /** Set headline priority. */
    @Serializable data class SetPriority(val priority: String? = null) : ActionDef
    /** Refile a heading to another location. */
    @Serializable data class Refile(val target: String? = null) : ActionDef
    /** Archive a heading (subtree or tag). */
    @Serializable data class Archive(val style: String = "default") : ActionDef
    /** Unknown action token. */
    @Serializable data class Unknown(val token: String) : ActionDef

    fun toToken(): String = when (this) {
        is SetTodo -> "todo($keyword)"
        is Schedule -> "schedule"
        is SetDeadline -> "deadline"
        is SetTags -> if (tags.isEmpty()) "tags" else "tags(${tags.joinToString(",")})"
        is SetPriority -> if (priority == null) "priority" else "priority($priority)"
        is Refile -> if (target == null) "refile" else "refile($target)"
        is Archive -> if (style == "default") "archive" else "archive($style)"
        is Unknown -> token
    }
}
