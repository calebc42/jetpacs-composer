// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

/**
 * Composer-side description of the FILTER subset implemented by
 * `jetpacs-crud--entry-matches-p` and `jetpacs-crud--note-matches-p`.
 *
 * The guided editor deliberately models a flat AND of common terms. Nested
 * expressions and org-ql-only terms remain valid in raw mode and round-trip
 * unchanged.
 */
object FilterQuery {

    enum class Term(val wireName: String, val label: String) {
        Todo("todo", "TODO state"),
        Done("done", "Done"),
        Tags("tags", "Tags"),
        Priority("priority", "Priority"),
        Heading("heading", "Heading contains"),
        Regexp("regexp", "Text regexp"),
        Property("property", "Property"),
        Level("level", "Outline level"),
        Scheduled("scheduled", "Has scheduled date"),
        Deadline("deadline", "Has deadline"),
    }

    data class Clause(
        val term: Term,
        /** Property name for [Term.Property]; otherwise unused. */
        val subject: String = "",
        /** Term arguments. Multiple values mean any-of where the runtime supports it. */
        val values: List<String> = emptyList(),
    )

    sealed interface ParseResult {
        data class Guided(val clauses: List<Clause>) : ParseResult
        data class Raw(val reason: String) : ParseResult
        data class Invalid(val message: String) : ParseResult
    }

    /**
     * Every heading-family kind — records AND notes — now shares one term
     * set: the runtime evaluates the same subset off the `vulpea-note'
     * struct (`jetpacs-crud--note-matches-p'), and a term beyond it falls to
     * org-ql over the source files. [kind] is retained for API stability but
     * no longer restricts the allowed terms.
     */
    fun allowedTerms(kind: ViewKind): List<Term> = Term.entries.toList()

    fun parse(query: String, kind: ViewKind): ParseResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return ParseResult.Guided(emptyList())
        if (!trimmed.startsWith("(") && !trimmed.startsWith("'(")) {
            return parseTokens(trimmed, kind)
        }
        return runCatching { SexpParser(trimmed).parse() }
            .fold(
                onSuccess = { parseSexp(it, kind) },
                onFailure = { ParseResult.Invalid(it.message ?: "Malformed filter expression") },
            )
    }

    fun serialize(clauses: List<Clause>): String {
        if (clauses.isEmpty()) return ""
        val encoded = clauses.map(::serializeClause)
        return if (encoded.size == 1) encoded.single()
        else "(and ${encoded.joinToString(" ")})"
    }

    fun validate(clauses: List<Clause>, @Suppress("UNUSED_PARAMETER") kind: ViewKind): List<String> = buildList {
        clauses.forEachIndexed { index, clause ->
            val prefix = "Clause ${index + 1} (${clause.term.label})"
            if (clause.term == Term.Property && clause.subject.isBlank())
                add("$prefix needs a property")
            if (clause.term == Term.Level &&
                (clause.values.size !in 1..2 || clause.values.any { it.toIntOrNull() == null }))
                add("$prefix needs one level or a min,max pair")
        }
    }

    private fun parseTokens(query: String, kind: ViewKind): ParseResult {
        val clauses = mutableListOf<Clause>()
        for (token in tokenizeWords(query)) {
            val clause = when {
                token.startsWith("todo:") ->
                    Clause(Term.Todo, values = splitValues(token.substring(5)))
                token.startsWith("tags:") ->
                    Clause(Term.Tags, values = splitValues(token.substring(5)))
                token.startsWith("priority:") ->
                    Clause(Term.Priority, values = splitValues(token.substring(9)))
                else -> return ParseResult.Raw(
                    "Free-text filters stay in raw mode so their literal matching is preserved")
            }
            if (clause.values.isEmpty())
                return ParseResult.Invalid("${clause.term.label} needs at least one value")
            clauses += clause
        }
        return ParseResult.Guided(clauses)
    }

    private fun parseSexp(root: Sexp, @Suppress("UNUSED_PARAMETER") kind: ViewKind): ParseResult {
        val forms = when {
            root is Sexp.ListForm && root.head == "and" -> root.items.drop(1)
            root is Sexp.ListForm && root.head in setOf("or", "not") ->
                return ParseResult.Raw("Nested boolean expressions are edited in raw mode")
            else -> listOf(root)
        }
        val clauses = mutableListOf<Clause>()
        for (form in forms) {
            val parsed = parseClause(form)
                ?: return ParseResult.Raw(
                    "This expression uses nesting or a term that needs raw/org-ql mode")
            clauses += parsed
        }
        return ParseResult.Guided(clauses)
    }

    private fun parseClause(form: Sexp): Clause? {
        if (form !is Sexp.ListForm) return null
        val term = Term.entries.firstOrNull { it.wireName == form.head } ?: return null
        val args = form.items.drop(1).map { (it as? Sexp.Atom)?.value ?: return null }
        return when (term) {
            Term.Done, Term.Scheduled, Term.Deadline ->
                if (args.isEmpty()) Clause(term) else null
            Term.Property -> when (args.size) {
                1 -> Clause(term, subject = args[0])
                2 -> Clause(term, subject = args[0], values = listOf(args[1]))
                else -> null
            }
            Term.Level -> if (args.size in 1..2 && args.all { it.toIntOrNull() != null })
                Clause(term, values = args) else null
            Term.Priority -> if (args.firstOrNull() in setOf("<", "<=", ">", ">=", "="))
                null else Clause(term, values = args)
            Term.Todo, Term.Tags, Term.Heading, Term.Regexp -> Clause(term, values = args)
        }
    }

    private fun serializeClause(clause: Clause): String = when (clause.term) {
        Term.Done, Term.Scheduled, Term.Deadline -> "(${clause.term.wireName})"
        Term.Property -> buildString {
            append("(property ").append(quote(clause.subject))
            clause.values.firstOrNull()?.let { append(' ').append(quote(it)) }
            append(')')
        }
        Term.Level -> "(level ${clause.values.joinToString(" ")})"
        else -> "(${clause.term.wireName}${
            clause.values.joinToString(separator = "", prefix = "") { " ${quote(it)}" }
        })"
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(ch)
            }
        }
        append('"')
    }

    private fun splitValues(value: String): List<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty)

    private fun tokenizeWords(value: String): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && value[index].isWhitespace()) index++
            if (index == value.length) break
            if (value[index] == '"') {
                val end = value.indexOf('"', index + 1)
                if (end < 0) return listOf(value)
                out += value.substring(index + 1, end)
                index = end + 1
            } else {
                val end = value.indexOfFirst(index) { it.isWhitespace() }
                if (end < 0) {
                    out += value.substring(index)
                    break
                }
                out += value.substring(index, end)
                index = end
            }
        }
        return out
    }

    private inline fun String.indexOfFirst(start: Int, predicate: (Char) -> Boolean): Int {
        for (i in start until length) if (predicate(this[i])) return i
        return -1
    }

    private sealed interface Sexp {
        data class Atom(val value: String) : Sexp
        data class ListForm(val items: List<Sexp>) : Sexp {
            val head: String? get() = (items.firstOrNull() as? Atom)?.value
        }
    }

    private class SexpParser(private val source: String) {
        private var index = 0

        fun parse(): Sexp {
            skipSpace()
            if (peek('\'')) index++
            val result = parseOne()
            skipSpace()
            require(index == source.length) { "Unexpected text after filter expression" }
            return result
        }

        private fun parseOne(): Sexp {
            skipSpace()
            require(index < source.length) { "Unexpected end of filter expression" }
            return when (source[index]) {
                '(' -> parseList()
                '"' -> Sexp.Atom(parseString())
                else -> Sexp.Atom(parseSymbol())
            }
        }

        private fun parseList(): Sexp.ListForm {
            index++
            val items = mutableListOf<Sexp>()
            while (true) {
                skipSpace()
                require(index < source.length) { "Missing ')' in filter expression" }
                if (source[index] == ')') {
                    index++
                    return Sexp.ListForm(items)
                }
                items += parseOne()
            }
        }

        private fun parseString(): String {
            index++
            val out = StringBuilder()
            while (index < source.length) {
                val ch = source[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < source.length) { "Trailing escape in filter string" }
                        out.append(when (val escaped = source[index++]) {
                            'n' -> '\n'
                            else -> escaped
                        })
                    }
                    else -> out.append(ch)
                }
            }
            error("Missing closing quote in filter expression")
        }

        private fun parseSymbol(): String {
            val start = index
            while (index < source.length &&
                !source[index].isWhitespace() && source[index] !in listOf('(', ')')) index++
            require(index > start) { "Expected filter term" }
            return source.substring(start, index)
        }

        private fun skipSpace() {
            while (index < source.length && source[index].isWhitespace()) index++
        }

        private fun peek(ch: Char) = index < source.length && source[index] == ch
    }
}
