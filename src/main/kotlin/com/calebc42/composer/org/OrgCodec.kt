// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.org

import com.calebc42.composer.model.AppSpec
import com.calebc42.composer.model.BodyElement
import com.calebc42.composer.model.ChecklistItem
import com.calebc42.composer.model.ColType
import com.calebc42.composer.model.SchemaField
import com.calebc42.composer.model.SourceRef
import com.calebc42.composer.model.ViewKind
import com.calebc42.composer.model.ViewSpec

/**
 * Reader/writer for the frozen v1 app.org format (docs/FORMAT.md).
 *
 * This is the composer's only org surface: a strict canonical subset —
 * file keywords, level-1 headings, property drawers, tables, checkbox
 * lists — with everything unmodelled preserved verbatim as raw blocks.
 * It deliberately parses the SAME grammar as jetpacs-crud-orgapp.el;
 * the shared fixture corpus (elisp/test/fixtures) keeps the two honest.
 */
object OrgCodec {

    class FormatException(message: String) : Exception(message)

    // ─── Parsing ─────────────────────────────────────────────────────────

    private val KEYWORD_RE =
        Regex("""^#\+(JETPACS_APP|JETPACS_ICON|JETPACS_ORDER|JETPACS_APP_FORMAT|TITLE):\s*(.*?)\s*$""",
              RegexOption.IGNORE_CASE)
    private val HEADING_RE = Regex("""^\* +(.*?)\s*$""")
    private val DRAWER_START_RE = Regex("""^\s*:PROPERTIES:\s*$""", RegexOption.IGNORE_CASE)
    private val DRAWER_END_RE = Regex("""^\s*:END:\s*$""", RegexOption.IGNORE_CASE)
    private val DRAWER_PROP_RE = Regex("""^\s*:([A-Za-z_]+):\s*(.*?)\s*$""")
    private val TABLE_LINE_RE = Regex("""^\s*\|.*$""")
    private val TABLE_RULE_RE = Regex("""^\s*\|[-+].*$""")
    private val CHECKBOX_RE = Regex("""^\s*[-+*] +\[([ xX-])\] +(.*)$""")
    private val COLTYPE_RE = Regex("""([a-z]+)(\(([^)]*)\))?""")
    private val SCHEMA_RE = Regex("""%([A-Za-z_][A-Za-z_0-9-]*)(\(([^)]*)\))?""")

    fun parse(text: String): AppSpec {
        val lines = text.lines()

        val keywords = mutableMapOf<String, String>()
        for (line in lines) {
            KEYWORD_RE.matchEntire(line)?.let { m ->
                keywords.putIfAbsent(m.groupValues[1].uppercase(), m.groupValues[2])
            }
        }

        val id = keywords["JETPACS_APP"]
            ?: throw FormatException("missing #+JETPACS_APP: keyword")
        if (!AppSpec.ID_RE.matches(id))
            throw FormatException("app id must match [a-z][a-z0-9-]*, got \"$id\"")
        keywords["JETPACS_APP_FORMAT"]?.let {
            if (it.trim() != "1")
                throw FormatException("unsupported JETPACS_APP_FORMAT \"$it\"")
        }

        val views = mutableListOf<ViewSpec>()
        var i = 0
        while (i < lines.size && !HEADING_RE.matches(lines[i])) i++
        var index = 0
        while (i < lines.size) {
            val title = HEADING_RE.matchEntire(lines[i])!!.groupValues[1]
            i++
            val (props, afterDrawer) = parseDrawer(lines, i)
            i = afterDrawer
            val bodyStart = i
            while (i < lines.size && !HEADING_RE.matches(lines[i])) i++
            views += buildView(title, props, lines.subList(bodyStart, i), index)
            index++
        }
        if (views.isEmpty())
            throw FormatException("an app needs at least one view (a level-1 heading)")
        val slugs = views.map { it.name }
        if (slugs.size != slugs.distinct().size)
            throw FormatException("two views slugify to the same name; retitle one")

        return AppSpec(
            id = id,
            label = keywords["TITLE"],
            icon = keywords["JETPACS_ICON"],
            order = keywords["JETPACS_ORDER"]?.trim()?.toIntOrNull(),
            views = views,
        )
    }

    private fun parseDrawer(lines: List<String>, start: Int): Pair<Map<String, String>, Int> {
        var i = start
        while (i < lines.size && lines[i].isBlank()) i++
        if (i >= lines.size || !DRAWER_START_RE.matches(lines[i])) return emptyMap<String, String>() to start
        i++
        val props = mutableMapOf<String, String>()
        while (i < lines.size && !DRAWER_END_RE.matches(lines[i])) {
            DRAWER_PROP_RE.matchEntire(lines[i])?.let { m ->
                props[m.groupValues[1].uppercase()] = m.groupValues[2]
            }
            i++
        }
        if (i >= lines.size) throw FormatException("property drawer missing :END:")
        return props to i + 1
    }

    private fun buildView(title: String, props: Map<String, String>,
                          bodyLines: List<String>, index: Int): ViewSpec {
        val kind = when (props["KIND"]?.trim()?.lowercase()) {
            null, "", "table" -> ViewKind.TABLE
            "checklist" -> ViewKind.CHECKLIST
            "records" -> ViewKind.RECORDS
            else -> throw FormatException("unknown KIND \"${props["KIND"]}\" under \"$title\"")
        }
        if (kind == ViewKind.RECORDS && props["SCHEMA"].isNullOrBlank())
            throw FormatException("a records view needs a :SCHEMA: under \"$title\"")
        return ViewSpec(
            title = title,
            icon = props["ICON"],
            order = props["ORDER"]?.trim()?.toIntOrNull() ?: (10 * (index + 1)),
            kind = kind,
            source = props["SOURCE"]?.let { parseSource(it) },
            colTypes = props["COLTYPES"]?.let { parseColTypes(it) } ?: emptyList(),
            columns = props["COLUMNS"]?.split("|")?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: emptyList(),
            schema = props["SCHEMA"]?.let { parseSchema(it) } ?: emptyList(),
            filter = props["FILTER"]?.trim()?.ifEmpty { null },
            body = parseBody(bodyLines),
        )
    }

    private fun parseSchema(value: String): List<SchemaField> {
        val fields = SCHEMA_RE.findAll(value).map { m ->
            SchemaField.of(m.groupValues[1],
                           m.groupValues[3].ifEmpty { null })
        }.toList()
        if (fields.isEmpty())
            throw FormatException("SCHEMA needs at least one %PROP token")
        val names = fields.map { it.prop.uppercase() }
        if (names.size != names.distinct().size)
            throw FormatException("duplicate property in SCHEMA")
        return fields
    }

    private fun parseSource(value: String): SourceRef? {
        val v = value.trim()
        if (v.equals("inline", ignoreCase = true)) return null
        val parts = v.split("::", limit = 2)
        val heading = parts.getOrNull(1)?.trim()?.let {
            if (!it.startsWith("*"))
                throw FormatException("SOURCE target must be *Heading, got \"$it\"")
            it.removePrefix("*").trim()
        }
        return SourceRef(parts[0].trim(), heading)
    }

    private fun parseColTypes(value: String): List<ColType> =
        COLTYPE_RE.findAll(value).map { m ->
            val token = m.groupValues[1]
            val options = if (m.groupValues[2].isEmpty()) null else m.groupValues[3]
            when (token) {
                "text" -> ColType.Text
                "number" -> ColType.Number
                "date" -> ColType.Date
                "checkbox" -> ColType.Checkbox
                "enum" -> {
                    val opts = options?.split(",")?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                    if (opts.isNullOrEmpty())
                        throw FormatException("enum needs options, e.g. enum(A,B)")
                    ColType.Enum(opts)
                }
                else -> throw FormatException("unknown column type \"$token\" in COLTYPES")
            }.also {
                if (token != "enum" && options != null)
                    throw FormatException("$token takes no options in COLTYPES")
            }
        }.toList()

    private fun parseBody(lines: List<String>): List<BodyElement> {
        val elements = mutableListOf<BodyElement>()
        val raw = mutableListOf<String>()

        fun flushRaw() {
            val text = raw.joinToString("\n").trim('\n')
            if (text.isNotBlank()) elements += BodyElement.Raw(text)
            raw.clear()
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                TABLE_LINE_RE.matches(line) -> {
                    flushRaw()
                    val block = mutableListOf<String>()
                    while (i < lines.size && TABLE_LINE_RE.matches(lines[i])) {
                        block += lines[i]; i++
                    }
                    elements += parseTable(block)
                }
                CHECKBOX_RE.matches(line) -> {
                    flushRaw()
                    val items = mutableListOf<ChecklistItem>()
                    while (i < lines.size) {
                        val m = CHECKBOX_RE.matchEntire(lines[i]) ?: break
                        items += ChecklistItem(m.groupValues[1], m.groupValues[2])
                        i++
                    }
                    elements += BodyElement.Checklist(items)
                }
                else -> { raw += line; i++ }
            }
        }
        flushRaw()
        return elements
    }

    private fun parseTable(block: List<String>): BodyElement.Table {
        val rows = block.filterNot { TABLE_RULE_RE.matches(it) }.map { line ->
            val trimmed = line.trim()
            val inner = trimmed.removePrefix("|").removeSuffix("|")
            inner.split("|").map { it.trim() }
        }
        if (rows.isEmpty()) return BodyElement.Table(emptyList(), emptyList())
        return BodyElement.Table(header = rows.first(), rows = rows.drop(1))
    }

    // ─── Writing (canonical form) ─────────────────────────────────────────

    fun write(spec: AppSpec): String = buildString {
        appendLine("#+JETPACS_APP: ${spec.id}")
        spec.label?.let { appendLine("#+TITLE: $it") }
        spec.icon?.let { appendLine("#+JETPACS_ICON: $it") }
        spec.order?.let { appendLine("#+JETPACS_ORDER: $it") }
        for (view in spec.views) {
            appendLine()
            appendLine("* ${view.title}")
            writeDrawer(view)
            for (element in view.body) {
                appendLine()
                when (element) {
                    is BodyElement.Table -> append(writeTable(element))
                    is BodyElement.Checklist ->
                        element.items.forEach { appendLine("- [${it.state}] ${it.text}") }
                    is BodyElement.Raw -> appendLine(element.text)
                }
            }
        }
    }

    private fun StringBuilder.writeDrawer(view: ViewSpec) {
        val props = buildList {
            view.icon?.let { add("ICON" to it) }
            view.order?.let { add("ORDER" to it.toString()) }
            when (view.kind) {
                ViewKind.CHECKLIST -> add("KIND" to "checklist")
                ViewKind.RECORDS -> add("KIND" to "records")
                ViewKind.TABLE -> {}
            }
            view.source?.let {
                add("SOURCE" to (it.file + (it.heading?.let { h -> "::*$h" } ?: "")))
            }
            if (view.schema.isNotEmpty())
                add("SCHEMA" to view.schema.joinToString(" ") { f ->
                    "%${f.prop}" + (f.label?.let { "($it)" } ?: "")
                })
            if (view.colTypes.isNotEmpty())
                add("COLTYPES" to view.colTypes.joinToString(" ") { it.toToken() })
            if (view.columns.isNotEmpty())
                add("COLUMNS" to view.columns.joinToString(" | "))
            view.filter?.let { add("FILTER" to it) }
        }
        if (props.isEmpty()) return
        appendLine(":PROPERTIES:")
        props.forEach { (k, v) -> appendLine(":$k: $v") }
        appendLine(":END:")
    }

    private fun writeTable(table: BodyElement.Table): String {
        val all = listOf(table.header) + table.rows
        val ncols = all.maxOf { it.size }
        val widths = IntArray(ncols) { c -> all.maxOf { it.getOrElse(c) { "" }.length } }
        fun row(cells: List<String>) = (0 until ncols).joinToString(
            separator = " | ", prefix = "| ", postfix = " |",
        ) { c -> cells.getOrElse(c) { "" }.padEnd(widths[c]) }
        return buildString {
            appendLine(row(table.header))
            appendLine((0 until ncols).joinToString(
                separator = "+", prefix = "|", postfix = "|",
            ) { c -> "-".repeat(widths[c] + 2) })
            table.rows.forEach { appendLine(row(it)) }
        }
    }
}
