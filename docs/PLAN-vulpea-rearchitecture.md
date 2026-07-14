# Plan: rearchitect jetpacs-composer on vulpea + MELPA engines

**STATUS (2026-07-13): Phases 0–3 landed.** The full ERT suite (74/74)
and bundle smoke (pantry + hello-world) are green under `VULPEA_DIR`-enabled
WSL. **Every datasource kind now reads from vulpea:** heading-family kinds
(records/board/calendar/gallery/tree/dashboard/gantt/notes) from the note
index, and `table`/`checklist` from a plugin extractor. Registration
id-adopts source files; writes re-index; all kinds degrade to a "needs
vulpea" placeholder on bare core. Records address by their stable `:ID:`
(resolved live, robust to offset shifts) and the record/note cards are
unified. Phase 1 (M9) added `#+JETPACS_DEPENDS:` + the composer's
engine-install bootstrap. Phases 4–5 (Kotlin alignment, module split +
dead-code sweep) remain.

Deferred (optional hardening, not blocking): table/checklist mutations
address by a `pos` hint from the fresh index (reindex-on-write keeps it
current) rather than the plan's full `(heading, tbl, row, col)` logical
addressing; and the vulpea code still lives in `jetpacs-crud.el` rather
than a split-out `jetpacs-crud-vulpea.el` (Phase 5).

**Known pre-existing failures (not introduced here, tracked for later):**
three JVM `OrgCodecTest` cases are red from before Phase 1 —
`writerEmitsParsableCanonicalForm` and `formatTwoIsTheCleanCutoverVersion`
still assume the format-2 era (the writer now emits format 3 and old
formats are accepted per FORMAT.md), and `sharedParserParityManifest`
hits a JVM-vs-elisp divergence on unknown-`:KIND:` leniency (the elisp
parser accepts it, the JVM parser demands a `:SCHEMA:`). Fixing these is
a small parser-parity cleanup plus a decision on whether old format
versions should be rejected outright.

## Why

jetpacs-composer currently reimplements too much in elisp: a hand-rolled
org-ql-subset FILTER interpreter, bespoke org-table/records/tree scanners,
and per-kind rendering paths — while [vulpea](https://github.com/d12frosted/vulpea)
v2.5 (standalone, no org-roam dependency; own SQLite DB, extractor plugin
system, schema validation, async autosync) already provides a robust PKM
foundation designed for exactly this. The decided change: **vulpea becomes
the canonical read path for every datasource kind** (a full rewrite,
including org tables via a custom extractor), **vulpea/org-ql become
injected-by-default dependencies** that the composer installs on-device
for new users (this finishes the previously-unfinished milestone M9),
while **graceful degradation is kept** — tri-state probes and placeholder
rendering so a bundle still loads on bare `jetpacs-core`. Delivered as one
phased plan: dependency injection first, then the runtime rewrite.

**Target repo:** `C:\Users\caleb\AndroidStudioProjects\jetpacs-composer`
(dev home — the `~/pkb/projects/jetpacs-composer` checkout is intentionally
empty, see project memory). Vulpea reference checkout:
`~/pkb/resources/emacs/vulpea`. Reference patterns: the `Glasspane`
sibling repo (`docs/starter-init.el`,
`emacs/apps/glasspane/glasspane-vulpea.el`,
`docs/PLAN-vulpea-ecosystem-exploration.md`).

## Verified facts (checked in source, not assumed)

- `elisp/jetpacs-crud.el` already called `jetpacs-org-parse-query` /
  `jetpacs-org-entry-matches-p` in the working tree, but the composer's
  `jetpacs` submodule was pinned to a commit (`f58dc2b`) that lacked
  `emacs/core/jetpacs-org.el` / `jetpacs-source.el` — those files exist in
  Glasspane's newer jetpacs checkout. **The submodule bump was a hard
  prerequisite** for the working tree to even load. Bumped to `40d4972`
  (Phase 0, done).
- Vulpea's extractor contract (`vulpea-db-extract.el`) is
  `make-vulpea-extractor :name :version :priority :schema :extract-fn`
  only — the extract-fn takes `(ctx note-data)`, runs inside the write
  transaction, and does its own `emacsql` inserts into plugin tables;
  deletes come free via FK `:on-delete :cascade` to `notes(id)`.
  Glasspane's `glasspane-vulpea.el` uses an older `:batch-insert-fn`/
  `:delete-fn` shape that **this vulpea version does not have** — do not
  copy it verbatim when Phase 3 lands.
- Extractors only fire for notes that get indexed, and vulpea only
  indexes file-level notes and headings that carry `:ID:`. A table/
  checklist extractor (Phase 3) therefore needs an "adopt IDs" pass on
  the source file first.
- `vulpea-note` carries todo/priority/scheduled/deadline/tags/level/
  title/properties/outline-path/pos — the entire current FILTER subset
  (`todo done tags priority heading regexp property level scheduled
  deadline`) is evaluable as an elisp predicate over notes, except `done`
  (per-file DONE keyword sets aren't indexed by vulpea; approximate with
  the global `org-done-keywords` + the `closed` slot, or fall to org-ql).
- The notes kind already has the write loop to generalize:
  `jetpacs-crud--note-mutate` = edit at `org-find-entry-with-id` → save →
  `vulpea-db-update-file` → `jetpacs-shell-push` (read-after-write, no
  autosync race).
- Test harness: `elisp/test/run-tests.sh` runs under WSL; vulpea enters
  ERT via `$VULPEA_DIR` load-path + `package-activate-all` for emacsql/s/
  dash. The notes round-trip test shows the isolated-DB pattern (a temp
  `vulpea-db-location`).
- `docs/FORMAT.md` currently **falsely** claims starter-init enables
  `vulpea-db-autosync-mode` — Phase 1 makes that true.
- Glasspane's `docs/starter-init.el` installs `org-ql vulpea org-srs`
  from MELPA (unpinned) then does
  `(setq vulpea-db-sync-directories (list org-directory))` +
  `(vulpea-db-autosync-mode 1)` — the reference shape for Phase 1's
  Deployer bootstrap. Open risk: confirm MELPA actually serves vulpea
  v2.x before relying on it.

---

## Phase 0 — Prerequisites (done, 2026-07-13)

1. **Bumped the `jetpacs` submodule** to `40d4972` (ships
   `emacs/core/jetpacs-org.el` + `jetpacs-source.el`). The local
   `gradle.properties` daemon-idle-timeout tweak in the submodule
   checkout survived the bump.
2. Rewired `elisp/jetpacs-crud.el`'s two FILTER call sites and
   `elisp/test/crud-tests.el`'s query tests onto the core functions
   (`jetpacs-org-parse-query` / `jetpacs-org-entry-matches-p`) instead of
   the removed `jetpacs-crud--parse-query` / `--entry-matches-p`.
3. Fixed four latent bugs the bump's test run surfaced (all pre-existing,
   unmasked once the submodule actually loaded):
   - `jetpacs-crud--matrix-org-table`: the cell-escaping regex used a
     double-escaped replacement (`"\\\\vert{}"`) that emitted a literal
     backslash-backslash instead of `\vert{}` in exported org tables —
     fixed to `"\\vert{}"`.
   - `jetpacs-crud-derives-stable-date-reminder`: the test's fixture view
     plist was missing `:kind records`, so `jetpacs-crud--view-reminders`'s
     kind-gate silently produced `nil` — added the missing key.
   - `jetpacs-crud-hello-world-registers-scaffolds-and-lints` and
     `run-tests.sh`'s bundle smoke both asserted **6** shell views for
     `hello-world.org`; the fixture has grown to 11 declared views, 8 of
     which surface as distinct shell views (four `:GROUP: Tasks` members
     collapse into one) — updated both assertions to 8.
   - `jetpacs-crud-goldens`: the pantry fixture's wire-position goldens
     were stale by a constant 24-byte offset from unrelated upstream
     fixture edits; regenerated via `jetpacs-crud-tests-regen-goldens`
     and confirmed the diff was *only* the uniform position shift (byte-
     identical structure otherwise) before accepting it.
4. New `elisp/test/install-test-deps.sh` — one-time MELPA install of
   `emacsql s dash org-ql` into `$ELPA_DIR` (or the default
   `~/.emacs.d/elpa`); vulpea itself is never installed from an archive,
   only pointed at via `$VULPEA_DIR`.
5. `run-tests.sh` now prints a loud warning when `$VULPEA_DIR` is unset
   or missing, since after Phase 2 most suites will need it to avoid
   silently skipping. `crud-tests.el` also now respects `$ELPA_DIR`.

**Verified:** `VULPEA_DIR=~/pkb/resources/emacs/vulpea sh
elisp/test/run-tests.sh` → 66/66 ERT passed, `bundle smoke OK: pantry`,
`bundle smoke OK: hello-world`.

## Phase 1 — Dependency injection (M9). Independently landable, no runtime behavior change.

1. **`#+JETPACS_DEPENDS:` file-level keyword** (space-separated package
   names):
   - `elisp/jetpacs-crud-orgapp.el`: parse into spec `:depends`
     (validated `[a-z][a-z0-9-]*`); the runtime ignores it (it's
     deployment metadata), so old bundles stay valid.
   - `src/main/kotlin/com/calebc42/composer/model/AppSpec.kt`:
     `val depends: List<String> = emptyList()`; `org/OrgCodec.kt`: parse
     + write; the composer auto-adds `vulpea` for kinds that need it and
     `org-ql` when a FILTER parses to Raw/extended terms.
2. **`device/Deployer.kt` bootstrap**, mirroring
   `Glasspane/docs/starter-init.el`:
   - Extend `installSnippet`: `require 'package` + MELPA +
     `package-initialize`; per dep: `unless package-installed-p` → one
     `package-refresh-contents` → `package-install`, all wrapped in
     `condition-case` (offline never breaks startup, retried each
     launch); then
     `(when (require 'vulpea nil t) (setq vulpea-db-sync-directories (list org-directory <apps-dir>)) (vulpea-db-autosync-mode 1))`
     — the `jetpacs-crud/` apps dir must be in sync scope since inline
     sources live there; a first-run marker file gates one
     `vulpea-db-sync-full-scan`.
   - New `bootstrapDeps(serial)` running the same forms over the
     existing ssh/emacsclient channel for the live-loop path, so an
     already-running device can be provisioned without editing init.
     Idempotent by construction.
3. **`docs/FORMAT.md`**: document `#+JETPACS_DEPENDS:`; fix the autosync
   claim to point at the composer's install snippet (now true).
4. **Risk retire before shipping**: confirm MELPA serves vulpea ≥ 2.x.
   If not, fall back to `package-vc-install` pinned to a rev, with the
   composer UI surfacing which install path ran.

**Verify:** ERT for `:DEPENDS` parse (accept/reject) + Kotlin
`OrgCodecTest` round-trip; on-device smoke: fresh Termux Emacs → paste
snippet → `(featurep 'vulpea)` → t, autosync on; run twice for
idempotency.

## Phase 2 — Runtime foundation: heading-family kinds onto vulpea

All heading-shaped kinds (`records board calendar gallery tree dashboard
gantt notes`) read from the vulpea index; hand-rolled heading scanners
deleted.

**New `elisp/jetpacs-crud-vulpea.el`** (probe-gated, `declare-function`
stubs as today):
- Move the `jetpacs-crud--vulpea` tri-state probe here (keep the
  `jetpacs-shell-refresh-hook` reset).
- `jetpacs-crud-vulpea-ensure-source (spec view)` — ID-adoption at
  register/scaffold time: file-level `:ID:` (`org-id-get-create` at
  `point-min`), IDs on the source heading + direct-child record
  headings, save iff modified, `vulpea-db-update-file`. Document in
  FORMAT.md ("what the runtime does to your files"); no-op when IDs
  already exist; never touches files outside declared sources.
- `jetpacs-crud-vulpea-mutate (file &optional note-id) THUNK` —
  generalization of `--note-mutate`; **every mutation in every kind**
  funnels through this (edit → save → `vulpea-db-update-file` →
  `jetpacs-shell-push`).
- **FILTER compiler**: `jetpacs-crud--compile-filter TREE` →
  `(:pred CLOSURE)` (a vulpea-note→bool closure covering and/or/not/
  todo/done/tags/priority/heading/regexp/property/level/scheduled/
  deadline; absorbs `--note-matches-p`) or `(:org-ql TREE)` for
  out-of-subset terms.
- `jetpacs-crud--query-view-notes (spec view)` — the one scan replacing
  `--scan-records`/`--scan-notes`: SOURCE dispatch (`dir/` →
  `vulpea-db-query-by-directory`; `file::*Heading` →
  `vulpea-db-query-by-file-path` + outline-path filter; file/inline →
  level-1 notes). The `:org-ql` arm runs `org-ql-select` over the source
  file, maps positions→IDs, intersects; clear `user-error` naming org-ql
  when absent (today's contract). Optional cheap-index shortcut: single
  tags/property/level terms → `vulpea-db-query-by-tags-some`/
  `-by-property`/`-by-level` intersected with scope.

**`elisp/jetpacs-crud.el` changes:**
- All heading-kind bodies consume `--query-view-notes`; unified record
  shape `(:id ID :pos POS :fields ALIST :done BOOL)` off the note
  struct.
- Merge duplicated `--record-card`/`--note-card`; `crud.field.edit` +
  `crud.note.field.edit` both stay registered, both resolve by id.
  `--resolve`'s pos path survives only for table/checklist until
  Phase 3.
- Tree kind: `-by-file-path` at all levels + outline-path prefix;
  `crud.node.move` payload gains ids, relocates by id at mutation time.
- Ref machinery (`--ref-resolve`/`--ref-choices`) via
  `vulpea-db-get-by-id` (<5ms); delete scan-based resolution. Reminders
  use the unified scan.
- **Degradation**: factor `jetpacs-crud--needs-vulpea-body`; every
  heading-kind gate renders it when vulpea is absent. The bundle still
  loads on bare core.
- **Delete**: `--scan-records`, `--scan-notes`, `--scan-tree`,
  `--query-supported-p`, `--org-ql-positions`, `--note-matches-p`, the
  render-path `--scope-bounds` usage.
- `build-app-bundle.el` + `export/BundleExporter.kt`: inline order
  becomes `jetpacs-crud-vulpea.el`, `jetpacs-crud.el`,
  `jetpacs-crud-orgapp.el`.

**Verify:** a shared `jetpacs-crud-tests--with-vulpea` macro (temp vault
+ isolated `vulpea-db-location`, cloned from the notes round-trip test);
port the records/board/gantt/dashboard tests; FILTER-compiler unit tests
per term + an org-ql-only term (errors without org-ql, matches with);
degradation loop over all kinds (extend
`jetpacs-crud-notes-degrades-without-vulpea`); bundle smoke stays green
on bare core.

## Phase 3 — Tables & checklists via a jetpacs vulpea extractor

**Extractor** (in `jetpacs-crud-vulpea.el`):
- `jetpacs-crud-vulpea--extract (ctx note-data)` — file-level node only
  (`:level 0`); one walk of `(vulpea-parse-ctx-ast ctx)`; collects every
  table (cells + `:begin` positions + nearest-ancestor heading) and
  checkbox item; inserts via `emacsql (vulpea-db)` inside the
  transaction.
- Registered via `vulpea-db-register-extractor` +
  `make-vulpea-extractor :name 'jetpacs-crud :version 1 :priority 100
  :schema …` at load when vulpea is present, re-attempted on the
  refresh-hook probe; after first successful registration force
  `vulpea-db-update-file` on every registered spec's source files
  (registration isn't persisted).
- Tables (FK cascade to `notes(id)` = the file-level note):
  - `jetpacs_tables (note-id, heading, tbl-index, begin-pos, ncols,
    rows-json)` — one row per table, one query per render.
  - `jetpacs_checklist (note-id, heading, ordinal, state, text, pos)`.
- Readers `jetpacs-crud-vulpea-tables (file heading)` /
  `-checklist (file heading)` returning the plist shapes the renderers
  already consume.

**Runtime:** `--table-body`/`--checklist-body` read from the DB readers
(vulpea absent → placeholder — the decided trade: table views on bare
core now degrade). **Logical addressing replaces raw positions on the
wire** (DB positions go stale; never trust them for mutation): cell taps
carry `(view tbl heading row col)` + pos as an optimistic hint;
`jetpacs-crud--locate-table (file heading tbl-index)` re-locates at
mutation time (the survivor of `--find-table`); `--table-mutate-at` =
locate → `org-table-goto-line/column` → fn → align → save →
`vulpea-db-update-file` → push. Checklist toggle carries
`(ordinal text)`: pos-hint verify → scope re-scan → `user-error "Item
moved — pull to refresh"`. Delete render-path `--scan-table`,
`--scan-checklist`, `--cell-context`; export reads from the DB reader.

**Verify:** extractor round-trip ERT (stage vault → update-file →
readers return cells/positions); cell edit/toggle/row-add/CSV-import via
logical addressing; staleness test (edit file underneath, then mutate →
relocation or clean error); **regenerate goldens**
(`jetpacs-crud-tests-regen-goldens` — wire JSON changes; review the diff
deliberately); bundle smoke: bare core expects table/checklist
placeholders, second smoke leg with `VULPEA_DIR` asserts real bodies.

## Phase 4 — Kotlin alignment + on-device proof

1. `model/FilterQuery.kt`: one term set for all heading kinds matching
   the Phase-2 predicate subset; Raw-mode reason strings updated ("needs
   org-ql on the device").
2. Composer UI marks kinds "needs vulpea", auto-populates
   `#+JETPACS_DEPENDS:`.
3. `BundleExporter.kt` parity with `build-app-bundle.el` (three-part
   inline) — extend the CI equivalence pin.
4. On-device smoke script (documented in README): fresh device →
   "Setup device" (Phase-1 bootstrap) → deploy hello-world → all kinds
   render, cell edit round-trips, external edit via Termux `sed` +
   autosync refreshes the view.

## Phase 5 — Cleanup & docs

- FORMAT.md: rewrite the kinds/FILTER sections around the vulpea engine
  (index-backed subset table, org-ql extension, the ID-adoption
  contract, per-kind degradation matrix); replace the "positions never
  stale" preamble in `jetpacs-crud.el` with the logical-addressing
  contract.
- Sweep dead helpers + stale `declare-function` stubs; confirm the
  org-free-core guard test in the jetpacs submodule still passes.

## Ordering & risks

- Phase 1 ⟂ Phases 2–3; Phase 2 before 3; Phase 4 trails 2–3; goldens
  regen only in Phase 3.
- **MELPA vulpea version** — verify before Phase 1 ships; fallback
  `package-vc-install` pin.
- **ID adoption mutates user files** — gated behind register, no-op when
  IDs exist, declared sources only, documented.
- **Extractor registration timing** — forced per-source
  `vulpea-db-update-file` after registration closes the gap.
- **Per-file TODO/DONE semantics** — global-keyword approximation,
  org-ql fallback, FORMAT.md note.
- **Extractor speed** — linear AST walk, declared-source files only;
  everything else pays one `plist-get` miss.

## Verification summary

Per phase: ERT under WSL (`sh elisp/test/run-tests.sh`, `VULPEA_DIR` set
for vulpea suites), golden regen only at Phase 3, Kotlin `OrgCodecTest`,
bundle smoke on bare core (degradation) + vulpea-loaded leg, and an
on-device smoke (fresh Termux Emacs, snippet paste, hello-world deploy)
at Phases 1 and 4.

### Critical files
- `elisp/jetpacs-crud.el`
- `elisp/jetpacs-crud-vulpea.el` (new, Phase 2)
- `elisp/jetpacs-crud-orgapp.el`
- `elisp/test/crud-tests.el` (+ `run-tests.sh`, `install-test-deps.sh`)
- `docs/FORMAT.md`
- `src/main/kotlin/com/calebc42/composer/device/Deployer.kt`
- `src/main/kotlin/com/calebc42/composer/model/AppSpec.kt`
- `src/main/kotlin/com/calebc42/composer/org/OrgCodec.kt`
- `src/main/kotlin/com/calebc42/composer/model/FilterQuery.kt`
