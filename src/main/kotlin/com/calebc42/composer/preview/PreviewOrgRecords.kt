// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.preview

/** Which headings inside an org source become preview records. */
enum class PreviewOrgTraversal {
    /** Runtime-compatible records: only immediate children of the source root. */
    DIRECT_CHILDREN,

    /** Every heading below the source root, retaining its relative tree depth. */
    DESCENDANTS,
}

/**
 * Read-only options for [PreviewOrgRecords.read].
 *
 * [unscopedParentLevel] is zero for a normal org file.  Inline view bodies
 * retain their level-two record headings after OrgCodec removes the level-one
 * view heading, so callers use one for that input.  A resolved [headingScope]
 * supplies its own parent level and takes precedence.
 */
data class PreviewOrgReadOptions(
    val headingScope: String? = null,
    val traversal: PreviewOrgTraversal = PreviewOrgTraversal.DIRECT_CHILDREN,
    val unscopedParentLevel: Int = 0,
    /** Used when the input is an inline body without its file-level #+TODO. */
    val todoKeywords: Set<String> = emptySet(),
) {
    init {
        require(unscopedParentLevel >= 0) { "unscopedParentLevel must not be negative" }
    }
}

/** Org-specific hierarchy around the renderer-independent [record]. */
data class PreviewOrgRecordNode(
    val record: PreviewRecord,
    /** The literal number of stars on the source heading. */
    val sourceLevel: Int,
    /** Zero for a direct child, one for its child, and so on. */
    val treeDepth: Int,
    /** Index in this result, present only for a returned ancestor. */
    val parentIndex: Int?,
    /** Headline tags without their surrounding colons. */
    val tags: List<String>,
) {
    val explicitId: String?
        get() = record.values.entries
            .firstOrNull { it.key.equals("ID", ignoreCase = true) }
            ?.value
            ?.takeIf(String::isNotBlank)
}

/** A non-throwing projection result; a missing requested scope is explicit. */
data class PreviewOrgRecordSet(
    val nodes: List<PreviewOrgRecordNode>,
    val scopeFound: Boolean,
    val parentLevel: Int,
) {
    val records: List<PreviewRecord> get() = nodes.map { it.record }

    fun asInlineDataset(): PreviewDataset = PreviewDataset(
        provenance = PreviewProvenance.Inline,
        records = records,
    )
}

/**
 * Narrow org heading reader for semantic preview data.
 *
 * This deliberately is not a general org parser or a writer.  It recognizes
 * only the record shape used by FORMAT-2: headlines, planning timestamps, an
 * immediately following property drawer, and direct entry prose.  It performs
 * no filtering, inheritance, filesystem access, runtime calls, or mutation.
 */
object PreviewOrgRecords {
    fun read(
        orgText: String,
        options: PreviewOrgReadOptions = PreviewOrgReadOptions(),
    ): PreviewOrgRecordSet {
        val lines = normalizeLines(orgText)
        val todoKeywords = effectiveTodoKeywords(lines, options.todoKeywords)
        val headings = lines.mapIndexedNotNull { index, line ->
            HEADING.matchEntire(line)?.let { match ->
                ParsedHeading(
                    lineIndex = index,
                    level = match.groupValues[1].length,
                    headline = parseHeadline(match.groupValues[2], todoKeywords),
                )
            }
        }

        val normalizedScope = options.headingScope
            ?.trim()
            ?.trimStart('*')
            ?.trim()
            ?.takeIf(String::isNotEmpty)
        val scope = normalizedScope?.let { title ->
            headings.firstOrNull { it.headline.item == title }
        }
        if (normalizedScope != null && scope == null) {
            return PreviewOrgRecordSet(
                nodes = emptyList(),
                scopeFound = false,
                parentLevel = options.unscopedParentLevel,
            )
        }

        val parentLevel = scope?.level ?: options.unscopedParentLevel
        val firstRecordLevel = parentLevel + 1
        val scopeStartLine = scope?.lineIndex?.plus(1) ?: 0
        val scopeEndLine = scope?.let { scoped ->
            headings.firstOrNull {
                it.lineIndex > scoped.lineIndex && it.level <= scoped.level
            }?.lineIndex ?: lines.size
        } ?: lines.size

        val candidates = headings.filter { heading ->
            heading.lineIndex in scopeStartLine until scopeEndLine &&
                when (options.traversal) {
                    PreviewOrgTraversal.DIRECT_CHILDREN -> heading.level == firstRecordLevel
                    PreviewOrgTraversal.DESCENDANTS -> heading.level >= firstRecordLevel
                }
        }

        val nodes = mutableListOf<PreviewOrgRecordNode>()
        val ancestorIndexes = mutableListOf<Pair<Int, Int>>() // source level to result index
        candidates.forEach { heading ->
            while (ancestorIndexes.lastOrNull()?.first?.let { it >= heading.level } == true) {
                ancestorIndexes.removeLast()
            }
            val parentIndex = if (options.traversal == PreviewOrgTraversal.DESCENDANTS) {
                ancestorIndexes.lastOrNull()?.second
            } else {
                null
            }
            val entryEnd = headings.firstOrNull { it.lineIndex > heading.lineIndex }
                ?.lineIndex
                ?: lines.size
            val entry = parseEntry(
                lines = lines.subList(heading.lineIndex + 1, entryEnd),
                heading = heading,
            )
            val node = PreviewOrgRecordNode(
                record = PreviewRecord(
                    id = entry.values.valueIgnoringCase("ID")
                        ?.takeIf(String::isNotBlank)
                        ?: "preview-org-line-${heading.lineIndex + 1}",
                    title = heading.headline.item,
                    values = entry.values,
                    body = entry.body.takeIf(String::isNotBlank),
                ),
                sourceLevel = heading.level,
                treeDepth = (heading.level - firstRecordLevel).coerceAtLeast(0),
                parentIndex = parentIndex,
                tags = heading.headline.tags,
            )
            nodes += node
            if (options.traversal == PreviewOrgTraversal.DESCENDANTS) {
                ancestorIndexes += heading.level to nodes.lastIndex
            }
        }

        return PreviewOrgRecordSet(
            nodes = nodes,
            scopeFound = true,
            parentLevel = parentLevel,
        )
    }

    private fun parseEntry(
        lines: List<String>,
        heading: ParsedHeading,
    ): ParsedEntry {
        val properties = linkedMapOf<String, String>()
        var index = 0
        var metadata = true
        val prose = mutableListOf<String>()

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                metadata && trimmed.isBlank() -> index++

                metadata && PROPERTY_DRAWER_START.matches(trimmed) -> {
                    index++
                    while (index < lines.size && !PROPERTY_DRAWER_END.matches(lines[index].trim())) {
                        PROPERTY.matchEntire(lines[index].trim())?.let { match ->
                            val key = canonicalPropertyName(match.groupValues[1])
                            properties[key] = match.groupValues[2].trim()
                        }
                        index++
                    }
                    if (index < lines.size) index++
                }

                metadata && planningValues(trimmed).isNotEmpty() -> {
                    properties.putAll(planningValues(trimmed))
                    index++
                }

                else -> {
                    metadata = false
                    prose += line
                    index++
                }
            }
        }

        // Org headline specials win over any malformed same-named drawer key.
        properties["ITEM"] = heading.headline.item
        heading.headline.todo?.let { properties["TODO"] = it }
        heading.headline.priority?.let { properties["PRIORITY"] = it }
        if (heading.headline.tags.isNotEmpty()) {
            properties["TAGS"] = heading.headline.tags.joinToString(" ")
        }

        return ParsedEntry(
            values = properties,
            body = prose.joinToString("\n").trim(),
        )
    }

    private fun parseHeadline(raw: String, todoKeywords: Set<String>): ParsedHeadline {
        var remaining = raw.trim()
        val tagMatch = TAG_SUFFIX.find(remaining)
        val tags = tagMatch?.groupValues?.get(1)
            ?.split(':')
            ?.filter(String::isNotEmpty)
            .orEmpty()
        if (tagMatch != null) remaining = remaining.substring(0, tagMatch.range.first).trimEnd()

        val firstToken = remaining.substringBefore(' ', remaining)
        val todo = firstToken.takeIf(todoKeywords::contains)
        if (todo != null) remaining = remaining.removePrefix(todo).trimStart()

        val priorityMatch = PRIORITY.find(remaining)
            ?.takeIf { it.range.first == 0 }
        val priority = priorityMatch?.groupValues?.get(1)
        if (priorityMatch != null) {
            remaining = remaining.substring(priorityMatch.range.last + 1).trimStart()
        }

        return ParsedHeadline(
            item = remaining.trim(),
            todo = todo,
            priority = priority,
            tags = tags,
        )
    }

    private fun effectiveTodoKeywords(lines: List<String>, supplied: Set<String>): Set<String> {
        val fromFile = lines.flatMap { line ->
            TODO_DIRECTIVE.matchEntire(line)?.groupValues?.get(1)
                ?.split(Regex("\\s+"))
                ?.asSequence()
                ?.filter { it.isNotBlank() && it != "|" }
                ?.map { it.substringBefore('(') }
                ?.filter(String::isNotBlank)
                ?.toList()
                .orEmpty()
        }.toSet()
        return when {
            fromFile.isNotEmpty() -> fromFile
            supplied.isNotEmpty() -> supplied
            else -> DEFAULT_TODO_KEYWORDS
        }
    }

    private fun planningValues(line: String): Map<String, String> =
        PLANNING.findAll(line).associate { match ->
            match.groupValues[1].uppercase() to match.groupValues[2]
        }

    private fun canonicalPropertyName(raw: String): String =
        if (raw.uppercase() in CANONICAL_PROPERTIES) raw.uppercase() else raw

    private fun Map<String, String>.valueIgnoringCase(name: String): String? =
        entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    private fun normalizeLines(text: String): List<String> = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')

    private data class ParsedHeading(
        val lineIndex: Int,
        val level: Int,
        val headline: ParsedHeadline,
    )

    private data class ParsedHeadline(
        val item: String,
        val todo: String?,
        val priority: String?,
        val tags: List<String>,
    )

    private data class ParsedEntry(
        val values: Map<String, String>,
        val body: String,
    )

    private val HEADING = Regex("^(\\*+)\\s+(.*)$")
    private val TODO_DIRECTIVE = Regex(
        "^#\\+(?:TODO|SEQ_TODO|TYP_TODO):\\s*(.*)$",
        RegexOption.IGNORE_CASE,
    )
    private val TAG_SUFFIX = Regex("\\s+(:[^\\s:]+(?::[^\\s:]+)*:)\\s*$")
    private val PRIORITY = Regex("^\\[#([A-Za-z0-9])](?:\\s+|$)")
    private val PROPERTY_DRAWER_START = Regex(":PROPERTIES:", RegexOption.IGNORE_CASE)
    private val PROPERTY_DRAWER_END = Regex(":END:", RegexOption.IGNORE_CASE)
    private val PROPERTY = Regex("^:([A-Za-z_][A-Za-z_0-9-]*):\\s*(.*)$")
    private val PLANNING = Regex(
        "(?:^|\\s)(SCHEDULED|DEADLINE):\\s*(<[^>]+>|\\[[^]]+])",
        RegexOption.IGNORE_CASE,
    )

    private val CANONICAL_PROPERTIES = setOf(
        "ITEM", "TODO", "SCHEDULED", "DEADLINE", "PRIORITY", "TAGS", "ID",
    )
    private val DEFAULT_TODO_KEYWORDS = setOf(
        "TODO", "NEXT", "DOING", "WAITING", "DONE", "CANCELLED",
    )
}
