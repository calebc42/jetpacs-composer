// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.calebc42.composer.model.FilterQuery
import com.calebc42.composer.model.ViewKind

private enum class FilterEditorMode { Guided, Raw }

@Composable
fun ExpressionDialog(
    initialValue: String,
    viewKind: ViewKind,
    properties: List<String>,
    todoKeywords: List<String>,
    tags: List<String>,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialParse = remember(initialValue, viewKind) {
        FilterQuery.parse(initialValue, viewKind)
    }
    var mode by remember(initialValue, viewKind) {
        mutableStateOf(
            if (initialParse is FilterQuery.ParseResult.Guided)
                FilterEditorMode.Guided else FilterEditorMode.Raw)
    }
    var clauses by remember(initialValue, viewKind) {
        mutableStateOf(
            (initialParse as? FilterQuery.ParseResult.Guided)?.clauses ?: emptyList())
    }
    var guidedDirty by remember(initialValue, viewKind) { mutableStateOf(false) }
    var rawQuery by remember(initialValue) { mutableStateOf(initialValue) }
    val rawResult = FilterQuery.parse(rawQuery, viewKind)
    val rawError = (rawResult as? FilterQuery.ParseResult.Invalid)?.message
    val guidedErrors = FilterQuery.validate(clauses, viewKind)
    val canSave = if (mode == FilterEditorMode.Guided)
        guidedErrors.isEmpty() else rawError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Builder") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == FilterEditorMode.Guided) {
                        Button(onClick = {}) { Text("Guided") }
                        OutlinedButton(onClick = {
                            if (guidedDirty) rawQuery = FilterQuery.serialize(clauses)
                            mode = FilterEditorMode.Raw
                        }) {
                            Text("Raw")
                        }
                    } else {
                        OutlinedButton(onClick = {
                            val parsed = FilterQuery.parse(rawQuery, viewKind)
                            clauses = (parsed as? FilterQuery.ParseResult.Guided)
                                ?.clauses ?: emptyList()
                            guidedDirty = false
                            mode = FilterEditorMode.Guided
                        }) {
                            Text("Guided")
                        }
                        Button(onClick = {}) { Text("Raw") }
                    }
                }

                if (mode == FilterEditorMode.Guided) {
                    GuidedFilterEditor(
                        clauses = clauses,
                        allowedTerms = FilterQuery.allowedTerms(viewKind),
                        properties = properties,
                        todoKeywords = todoKeywords,
                        tags = tags,
                        onChange = {
                            clauses = it
                            guidedDirty = true
                        },
                    )
                    guidedErrors.forEach { error ->
                        Text(error, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    RawFilterEditor(rawQuery, viewKind, rawResult, { rawQuery = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        if (mode == FilterEditorMode.Guided)
                            FilterQuery.serialize(clauses) else rawQuery)
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun GuidedFilterEditor(
    clauses: List<FilterQuery.Clause>,
    allowedTerms: List<FilterQuery.Term>,
    properties: List<String>,
    todoKeywords: List<String>,
    tags: List<String>,
    onChange: (List<FilterQuery.Clause>) -> Unit,
) {
    Text(
        "These filters are ANDed together and use only terms implemented by " +
            "the device runtime. Switch to Raw for nested boolean or org-ql-only queries.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (clauses.isEmpty()) {
        Text("No filter clauses — every record is shown.")
    }
    clauses.forEachIndexed { index, clause ->
        GuidedClauseRow(
            clause = clause,
            allowedTerms = allowedTerms,
            properties = properties,
            todoKeywords = todoKeywords,
            tags = tags,
            onChange = { updated ->
                onChange(clauses.mapIndexed { i, old -> if (i == index) updated else old })
            },
            onRemove = { onChange(clauses.filterIndexed { i, _ -> i != index }) },
        )
    }

    var addExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { addExpanded = true }) { Text("+ Clause") }
        DropdownMenu(addExpanded, onDismissRequest = { addExpanded = false }) {
            allowedTerms.forEach { term ->
                DropdownMenuItem(
                    text = { Text(term.label) },
                    onClick = {
                        onChange(clauses + defaultClause(
                            term, properties, todoKeywords, tags))
                        addExpanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GuidedClauseRow(
    clause: FilterQuery.Clause,
    allowedTerms: List<FilterQuery.Term>,
    properties: List<String>,
    todoKeywords: List<String>,
    tags: List<String>,
    onChange: (FilterQuery.Clause) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        var termExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { termExpanded = true }) { Text(clause.term.label) }
            DropdownMenu(termExpanded, onDismissRequest = { termExpanded = false }) {
                allowedTerms.forEach { term ->
                    DropdownMenuItem(
                        text = { Text(term.label) },
                        onClick = {
                            onChange(defaultClause(term, properties, todoKeywords, tags))
                            termExpanded = false
                        },
                    )
                }
            }
        }

        when (clause.term) {
            FilterQuery.Term.Property -> PropertyClauseFields(
                clause, properties, onChange)
            FilterQuery.Term.Done,
            FilterQuery.Term.Scheduled,
            FilterQuery.Term.Deadline -> Text("present", style = MaterialTheme.typography.bodySmall)
            else -> {
                val suggestions = when (clause.term) {
                    FilterQuery.Term.Todo -> todoKeywords
                    FilterQuery.Term.Tags -> tags
                    FilterQuery.Term.Priority -> listOf("A", "B", "C")
                    else -> emptyList()
                }
                OutlinedTextField(
                    value = clause.values.joinToString(","),
                    onValueChange = { value ->
                        val values = if (clause.term in listOf(
                                FilterQuery.Term.Heading, FilterQuery.Term.Regexp)) {
                            value.takeIf(String::isNotEmpty)?.let(::listOf) ?: emptyList()
                        } else {
                            value.split(',').map(String::trim).filter(String::isNotEmpty)
                        }
                        onChange(clause.copy(values = values))
                    },
                    label = { Text(valueLabel(clause.term)) },
                    supportingText = if (suggestions.isEmpty()) null else ({
                        Text("Suggestions: ${suggestions.joinToString(", ")}")
                    }),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                if (suggestions.isNotEmpty()) {
                    SuggestionPicker(suggestions) { suggestion ->
                        if (suggestion !in clause.values)
                            onChange(clause.copy(values = clause.values + suggestion))
                    }
                }
            }
        }
        TextButton(onClick = onRemove) { Text("Remove") }
    }
}

@Composable
private fun SuggestionPicker(options: List<String>, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Add") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = {
                        onPick(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PropertyClauseFields(
    clause: FilterQuery.Clause,
    properties: List<String>,
    onChange: (FilterQuery.Clause) -> Unit,
) {
    var propertyExpanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { propertyExpanded = true }) {
            Text(clause.subject.ifBlank { "Property" })
        }
        DropdownMenu(propertyExpanded, onDismissRequest = { propertyExpanded = false }) {
            properties.forEach { property ->
                DropdownMenuItem(
                    text = { Text(property) },
                    onClick = {
                        onChange(clause.copy(subject = property))
                        propertyExpanded = false
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = clause.values.firstOrNull().orEmpty(),
        onValueChange = { value ->
            onChange(clause.copy(values = value.takeIf(String::isNotEmpty)?.let(::listOf)
                ?: emptyList()))
        },
        label = { Text("value (optional)") },
        singleLine = true,
        modifier = Modifier.weight(1f),
    )
}

@Composable
private fun RawFilterEditor(
    query: String,
    viewKind: ViewKind,
    parseResult: FilterQuery.ParseResult,
    onChange: (String) -> Unit,
) {
    val help = if (viewKind == ViewKind.NOTES) {
        "Notes filters run over the vulpea index. Supported terms are and/or/not, " +
            "todo, tags, property, regexp, and one exact level."
    } else {
        "Raw mode preserves hand-authored nested filters and terms supplied by " +
            "org-ql. Device-native terms are and/or/not, todo/done, tags, priority, " +
            "heading, regexp, property, level, scheduled, and deadline."
    }
    Text(
        help,
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        label = { Text("FILTER expression") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace),
        isError = parseResult is FilterQuery.ParseResult.Invalid,
        supportingText = {
            when (parseResult) {
                is FilterQuery.ParseResult.Invalid -> Text(parseResult.message)
                is FilterQuery.ParseResult.Raw -> Text(parseResult.reason)
                is FilterQuery.ParseResult.Guided ->
                    Text("This expression can also be edited in Guided mode.")
            }
        },
    )
}

private fun defaultClause(
    term: FilterQuery.Term,
    properties: List<String>,
    todoKeywords: List<String>,
    tags: List<String>,
): FilterQuery.Clause = when (term) {
    FilterQuery.Term.Property -> FilterQuery.Clause(
        term, subject = properties.firstOrNull().orEmpty())
    FilterQuery.Term.Todo -> FilterQuery.Clause(
        term, values = todoKeywords.firstOrNull()?.let(::listOf) ?: emptyList())
    FilterQuery.Term.Tags -> FilterQuery.Clause(
        term, values = tags.firstOrNull()?.let(::listOf) ?: emptyList())
    FilterQuery.Term.Priority -> FilterQuery.Clause(term, values = listOf("A"))
    FilterQuery.Term.Level -> FilterQuery.Clause(term, values = listOf("1"))
    else -> FilterQuery.Clause(term)
}

private fun valueLabel(term: FilterQuery.Term): String = when (term) {
    FilterQuery.Term.Todo -> "states (comma-separated; blank = any active)"
    FilterQuery.Term.Tags -> "tags (comma-separated; blank = any tag)"
    FilterQuery.Term.Priority -> "priorities (comma-separated)"
    FilterQuery.Term.Heading -> "text"
    FilterQuery.Term.Regexp -> "regular expression"
    FilterQuery.Term.Level -> "level, or min,max"
    else -> "values"
}
