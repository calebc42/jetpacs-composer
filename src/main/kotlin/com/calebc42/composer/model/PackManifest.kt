// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One engine pack's published contract — the parsed form of a
 * `*-pack.json` manifest (the committed reference is Glasspane's
 * `glasspane-pack.json`, generated live from `jetpacs-source-catalog` +
 * `jetpacs-action-catalog` by `glasspane-pack.el`).
 *
 * The manifest is what makes `pack:` references editable rather than
 * free-typed: it names the pack's `feature` (the Emacs feature the
 * runtime requires before resolving), its `depends` (what the Deployer
 * installs), and the typed `sources`/`actions` vocabulary the pickers
 * offer. Field names mirror the JSON keys on purpose.
 */
@Serializable
data class PackManifest(
    val pack_id: String,
    val pack_version: String,
    val min_jetpacs_api: String? = null,
    val feature: String? = null,
    val depends: List<Depend> = emptyList(),
    val layouts: List<String> = emptyList(),
    val sources: List<Source> = emptyList(),
    val actions: List<Action> = emptyList(),
) {
    @Serializable
    data class Depend(val name: String, val min_version: String? = null)

    /** A param or output field of a pack source (closed, source-side types). */
    @Serializable
    data class Field(
        val name: String,
        val type: String,
        val required: Boolean = false,
        val values: List<String> = emptyList(),
    )

    @Serializable
    data class Source(
        val name: String,
        val params: List<Field> = emptyList(),
        val fields: List<Field> = emptyList(),
    )

    @Serializable
    data class Action(
        val action: String,
        val doc: String? = null,
        val args: List<Field> = emptyList(),
    )

    fun source(name: String): Source? = sources.find { it.name == name }
    fun action(name: String): Action? = actions.find { it.action == name }

    /** Structural validation; returns this manifest or throws. */
    fun validated(): PackManifest {
        require(AppSpec.ID_RE.matches(pack_id)) {
            "pack_id must match [a-z][a-z0-9-]*, got \"$pack_id\""
        }
        require(pack_version.isNotBlank()) { "pack_version cannot be blank" }
        depends.forEach {
            require(AppSpec.DEPEND_RE.matches(it.name)) {
                "depends name must match [a-z][a-z0-9-]*, got \"${it.name}\""
            }
        }
        sources.forEach { source ->
            require(source.name.isNotBlank()) { "a source needs a name" }
            (source.params + source.fields).forEach { field ->
                require(field.type in SOURCE_FIELD_TYPES) {
                    "source ${source.name} field ${field.name}: unknown type " +
                        "\"${field.type}\" (want one of $SOURCE_FIELD_TYPES)"
                }
                if (field.type == "enum") require(field.values.isNotEmpty()) {
                    "source ${source.name} field ${field.name}: enum needs values"
                }
            }
        }
        actions.forEach { action ->
            require(action.action.isNotBlank()) { "an action needs a name" }
            action.args.forEach { arg ->
                require(arg.type in ACTION_ARG_TYPES) {
                    "action ${action.action} arg ${arg.name}: unknown type " +
                        "\"${arg.type}\" (want one of $ACTION_ARG_TYPES)"
                }
                if (arg.type == "enum") require(arg.values.isNotEmpty()) {
                    "action ${action.action} arg ${arg.name}: enum needs values"
                }
            }
        }
        return this
    }

    companion object {
        /** Source param/field types — mirrors `jetpacs-source-field-types`. */
        val SOURCE_FIELD_TYPES =
            setOf("text", "number", "boolean", "date", "string-list", "enum", "ref")

        /** Action arg types — mirrors the `jetpacs-defaction` arg schema. */
        val ACTION_ARG_TYPES = setOf("text", "number", "enum", "date", "ref", "bool")

        private val json = Json { ignoreUnknownKeys = true }

        /** Parse and structurally validate one manifest. */
        fun parse(text: String): PackManifest =
            json.decodeFromString(serializer(), text).validated()

        fun encode(manifest: PackManifest): String =
            json.encodeToString(serializer(), manifest)
    }
}

/**
 * The installed-manifest store: every `*-pack.json` in a directory the
 * user points at (Settings → "Pack manifest directory"), plus the
 * vendored Glasspane reference manifest as the zero-config default.
 *
 * A file that fails to parse becomes a [problems] entry, never an
 * exception. Duplicate pack ids fail closed: every manifest claiming the
 * contested id is excluded and the collision reported — the composer
 * must never silently pick one of two claimants (mirrors the runtime's
 * selection-time rule).
 */
class PackRegistry private constructor(
    val manifests: List<PackManifest>,
    val problems: List<String>,
) {
    fun byId(id: String): PackManifest? = manifests.find { it.pack_id == id }

    /**
     * The manifest backing pack pickers for one app: the explicitly
     * selected id when given, else the pack the document already
     * references (its first `pack:` source or action), else the sole
     * installed manifest.
     */
    fun selectedPack(spec: AppSpec, explicitId: String? = null): PackManifest? {
        explicitId?.let { return byId(it) }
        val referenced = spec.views.firstNotNullOfOrNull { view ->
            (view.source as? SourceRef.Pack)?.packId
                ?: view.actions.filterIsInstance<ActionDef.PackAction>()
                    .firstOrNull()?.packId
        }
        referenced?.let { return byId(it) }
        return manifests.singleOrNull()
    }

    companion object {
        val EMPTY = PackRegistry(emptyList(), emptyList())

        /** The vendored reference manifest (committed at src/main/resources). */
        fun builtIn(): PackRegistry {
            val text = PackRegistry::class.java
                .getResourceAsStream("/glasspane-pack.json")
                ?.bufferedReader(Charsets.UTF_8)?.readText()
                ?: return EMPTY
            return runCatching { PackRegistry(listOf(PackManifest.parse(text)), emptyList()) }
                .getOrElse { PackRegistry(emptyList(), listOf("built-in glasspane-pack.json: ${it.message}")) }
        }

        /**
         * Load every `*-pack.json` under [dir] (sorted by file name for
         * determinism), falling back to [builtIn] when no directory is
         * configured. A user manifest with the built-in's id replaces it.
         */
        fun load(dir: File?): PackRegistry {
            if (dir == null || !dir.isDirectory) return builtIn()
            val problems = mutableListOf<String>()
            val loaded = mutableListOf<Pair<String, PackManifest>>()
            dir.listFiles { f -> f.isFile && f.name.endsWith("-pack.json") }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    runCatching { PackManifest.parse(file.readText(Charsets.UTF_8)) }
                        .onSuccess { loaded += file.name to it }
                        .onFailure { problems += "${file.name}: ${it.message}" }
                }
            // Fail closed on contested ids: exclude every claimant.
            val byId = loaded.groupBy { it.second.pack_id }
            val manifests = mutableListOf<PackManifest>()
            byId.forEach { (id, claimants) ->
                if (claimants.size == 1) manifests += claimants.single().second
                else problems += "pack id \"$id\" is claimed by " +
                    claimants.joinToString(", ") { it.first } +
                    " — all excluded; remove the duplicates"
            }
            // The vendored reference joins unless a user manifest replaced it.
            builtIn().manifests.forEach { vendored ->
                if (manifests.none { it.pack_id == vendored.pack_id } &&
                    byId[vendored.pack_id] == null)
                    manifests += vendored
            }
            return PackRegistry(manifests, problems)
        }
    }
}
