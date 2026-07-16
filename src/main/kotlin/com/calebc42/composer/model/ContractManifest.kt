// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Contract(
    // contract_format 3 (the eabp extraction) renamed api_version to
    // reference_api_version: informational — the elisp reference client's
    // Tier-1 surface at generation time, not a wire number.
    val reference_api_version: String,
    val protocol_version: Int,
    val node_types: List<String>,
    val action_hook_keys: List<String>,
    val action_fields: List<String>,
    val offline_policies: List<String>,
    val offline_default: String,
)

object ContractManifest {
    val contract: Contract by lazy {
        val jsonText = ContractManifest::class.java.getResourceAsStream("/contract.json")
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: error("contract.json missing from resources")
        val json = Json { ignoreUnknownKeys = true }
        json.decodeFromString(Contract.serializer(), jsonText)
    }

    // Manifest-driven known sets for classification.
    // The forward-degrade sentinels (UNKNOWN/Unknown) stay as the degrade targets.
    
    val viewKinds: Set<String> = setOf(
        "table", "checklist", "records", "notes", "board", "calendar",
        "gallery", "tree", "dashboard", "gantt"
    )

    val colTypes: Set<String> = setOf(
        "text", "number", "date", "checkbox", "enum", "ref"
    )

    val actions: Set<String> = setOf(
        "todo", "schedule", "deadline", "tags", "priority", "refile", "archive"
    )
    
    val offlinePolicies: Set<String>
        get() = contract.offline_policies.toSet()
}
