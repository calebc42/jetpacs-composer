# Plan: Phase 2 — CRUD read/mutate path onto the canonical `jetpacs-org.el`

**Handoff for a fresh chat (written 2026-07-13).** This REFINES Phase 2 of the
pasted "rearchitect jetpacs-composer on vulpea + MELPA engines" plan. The pasted
Phase 2 would have built the vulpea read path *inside* the composer
(`jetpacs-crud-vulpea.el`: `--compile-filter`, `--note-matches-p`,
`--query-view-notes`). **Owner decision (this session): that DUPLICATES the
canonical `jetpacs-org.el`. Instead, extend the canonical `jetpacs-org.el` with
the vulpea read path (ONE grammar/engine), then the composer consumes it and
deletes its copies.** Directive verbatim: *"ensure you are using the canonical
jetpacs-org.el … don't duplicate any of its logic."*

## Where we are (verified 2026-07-13 — re-check, the owner develops here live)

- **Phase 0 + Phase 1 committed** (`9927ca1 feat(M9): #+JETPACS_DEPENDS + composer-injected engine bootstrap`, `63ccb7f docs: mark Phase 1 landed`). Composer `jetpacs` submodule at `40d4972`.
- **Baseline GREEN:** `VULPEA_DIR=~/pkb/resources/emacs/vulpea sh elisp/test/run-tests.sh` → **68/68 ERT + pantry & hello-world smokes**, "All suites passed." Run under WSL; repo is on `/mnt/c/Users/caleb/AndroidStudioProjects/jetpacs-composer`.
- **One uncommitted working-tree change (mine, keep it):** the export-matrix vulpea guard — the `'notes` case of `jetpacs-crud--export-matrix` wrapped in `(when (jetpacs-crud--vulpea-p) …)`. Without it the hello-world bundle smoke fails on bare core (`vulpea-db-query-by-directory` void via the export/top-bar path). Fold into Phase 2 or commit alongside.
- **Repos & paths (Windows checkouts; `.gitattributes eol=lf`):**
  - Canonical core to EXTEND: `C:\Users\caleb\AndroidStudioProjects\jetpacs\emacs\core\jetpacs-org.el` (dev home; **byte-identical** to the composer submodule @40d4972 — verified). Work on a branch; **owner pushes jetpacs**.
  - Consumer: `C:\Users\caleb\AndroidStudioProjects\jetpacs-composer\elisp\jetpacs-crud.el` (2897 lines) + `jetpacs-crud-orgapp.el`, `elisp/build-app-bundle.el`, `src/main/kotlin/.../export/BundleExporter.kt`.
  - Vulpea ref checkout: `~/pkb/resources/emacs/vulpea` (WSL); `VULPEA_DIR` points here for tests.
  - Run **all** git/tests under WSL for consistency. Owner edits concurrently — verify tree state (`git status`, `check-parens`) before each edit; coordinate.

## Canonical `jetpacs-org.el` today (357 lines, read it first)

Public API already provided (DO NOT re-implement in the composer):
`jetpacs-org-parse-query` (composer already uses ✓) · `jetpacs-org-entry-matches-p`
(**point-based** interpreter, full grammar) · `jetpacs-org--planning-match-p` ·
`jetpacs-org--entry-priority` · `jetpacs-org-query` (org-ql→fallback dispatch,
cached, over `org-agenda-files`) · `jetpacs-org-heading-ref` /
`jetpacs-org-resolve-ref` (id→pos→headline resolution) · `jetpacs-org-with-mutation`
(resolve→body→cache-invalidate→defer-save) + `jetpacs-org-set-property` /
`-toggle-todo` / `-set-planning` / `-defer-save` · `jetpacs-org-entry-typed-value`
(text/checkbox/date/enum/number/list) · `jetpacs-org-with-cache` /
`-cache-invalidate`. File header explicitly names `jetpacs-crud.el` a consumer.

## The duplication to eliminate (composer → canonical)

| `jetpacs-crud.el` (delete/route) | line | canonical replacement |
|---|---|---|
| `--note-matches-p`, `--note-planning-match`, `--note-priority-char` | 1140/1119/1110 | **NEW** `jetpacs-org-note-matches-p` (Part A) |
| `--query-supported-p`, `--org-ql-positions`, `--filter-index-supported-p`, `--compile-filter`, `--index-filter-terms`, `--scope-bounds` | 137/151/1197/1208/1192/170 | `jetpacs-org-query` dispatch + the note matcher |
| `--notes-query` (dir + file::heading dispatch) | 1091 | **NEW** `jetpacs-org-vulpea-source-notes`/`-query` (Part A) |
| `--resolve` (pos path, table) | 1849 | keep (table/pos, Phase 3) |
| `--note-resolve`+`--note-goto` (id path) | 2632/2649 | `vulpea-db-get-by-id` (already) + keep scope check `--note-in-source-p`; marker via `jetpacs-org-resolve-ref` |
| `--note-mutate` (edit→save→`vulpea-db-update-file`→push) | 2657 | `jetpacs-org-with-mutation` **+** a vulpea re-index tail (see reconcile note) |
| `--note-field` (PROP→note accessor) | 1075 | keep composer-side (schema convention), but read via canonical note accessors — not a grammar dup |

## Part A — Extend canonical `jetpacs-org.el` (jetpacs repo, on a branch)

**A1. One grammar over a data-accessor (removes the point-vs-index duplication).**
Refactor the interpreter so the pcase exists ONCE:
- `jetpacs-org--matches-p (tree GET)` — the pcase, each arm calling `(funcall GET 'WHAT …)`. `WHAT` ∈ `todo`(→keyword|nil), `tags`(→list), `priority`(→char|nil), `title`(→string), `level`(→int), `property NAME`(→string|nil), `planning WHICH`(→raw stamp string|nil, WHICH="SCHEDULED"/"DEADLINE"), `regexp-match RE`(→non-nil if RE hits the entry haystack).
- `jetpacs-org--point-get` — reads org **at point** (`org-get-todo-state`, `org-get-tags nil t`, `jetpacs-org--entry-priority`, `nth 4 (org-heading-components)`, `org-current-level`, `org-entry-get`, planning stamp via `org-entry-get`, regexp over the **body** `re-search-forward … (outline-next-heading)`).
- `jetpacs-org--note-get (note)` — reads a **`vulpea-note`** struct: `vulpea-note-todo`, `vulpea-note-tags`, priority-char (vulpea priority is a **char code** 65=A, may also be a string — normalize like the composer's `--note-priority-char`), `vulpea-note-title`, `vulpea-note-level`, `(cdr (assoc-string NAME (vulpea-note-properties note) t))` (drawer keys are **UPCASED**), planning via `vulpea-note-scheduled`/`-deadline`, regexp over **title + properties** (no body in the index — SEMANTIC DIFFERENCE, document it).
- `jetpacs-org-entry-matches-p (tree)` ≡ `(jetpacs-org--matches-p tree #'jetpacs-org--point-get)` — **preserve current behavior exactly** (existing pcase moves into `--point-get` + `--matches-p`).
- `jetpacs-org-note-matches-p (tree note)` ≡ `(jetpacs-org--matches-p tree (lambda (w &rest a) (apply #'jetpacs-org--note-get note w a)))`.
- Split planning into `jetpacs-org--planning-match-spec (stamp args)` (stamp string + :on/:from/:to → bool, reusing `jetpacs-org--planning-day`); both accessors return the stamp, `--matches-p` calls the spec matcher. `done`/`todo` use global `org-done-keywords` in BOTH paths (already consistent).
- `declare-function` the `vulpea-note-*` accessors + `require 'vulpea nil t` guarded (jetpacs core must still load with NO vulpea — the note path is only entered when vulpea is present).

**A2. Vulpea availability + scoped source query.**
- `jetpacs-org-vulpea-available-p` — `(and (require 'vulpea nil t) (fboundp 'vulpea-db-query) t)`.
- `jetpacs-org-vulpea-source-notes (source)` — `source` plist `(:dir D | :file F [:heading H])` → list of `vulpea-note` (mirror the composer's `--notes-query` L1091 exactly):
  - `:dir` → `(vulpea-db-query-by-directory (directory-file-name D) 0)` (file-level vault notes).
  - `:file` + `:heading` → notes in F with `level>0` and `(equal (vulpea-note-outline-path n) (list heading))` (id'd direct children of that heading).
  - `:file` no heading → level-1 notes of F. (Use `vulpea-db-query-by-file-path F [level]`, or the `vulpea-db-query` predicate the current `--notes-query` uses.)
- `jetpacs-org-vulpea-query (source tree)` → `source-notes`, filtered by `jetpacs-org-note-matches-p` when `tree` non-nil.

**A3. Tests** (`jetpacs/test/jetpacs-tests.el`, run `wsl -d Debian -- sh test/run-tests.sh`):
add `jetpacs-org-note-matches` — mock `vulpea-note-*` via `cl-letf` over plists (pattern: `jetpacs-tests--with-fake-vulpea` @ L2561) and assert the note matcher agrees with the point matcher on equivalent data across every term. Also add a direct `jetpacs-org-entry-matches-p` regression (currently only parse/typed/mutation are tested @ L932/941/952). No `VULPEA_DIR` needed if accessors are mocked.

**Reconcile note (mutation):** canonical `jetpacs-org-with-mutation` does
cache-invalidate + `jetpacs-org-defer-save`; the composer needs **immediate**
`save-buffer` + `vulpea-db-update-file` (read-after-write before the next phone
tap). Do NOT put `vulpea-db-update-file` in the core unconditionally. Options:
(a) composer wraps `jetpacs-org-with-mutation` then calls `vulpea-db-update-file`
+ `jetpacs-shell-push`; (b) add an optional `:sync`/re-index hook to the core
macro. **Recommend (a)** — keeps vulpea coupling out of the core.

## Part B — Consume in jetpacs-composer

1. **Bump** the composer `jetpacs` submodule to the extended commit (owner pushes jetpacs first). Tests use `-L jetpacs/emacs/core`; the device bundle inlines from the submodule.
2. **Delete** the duplicates (table above). Note the composer ALREADY routes records/notes through `jetpacs-defsource` (`crud.records`/`crud.notes`) + `jetpacs-source-query` — keep that; just swap the impl bodies to call canonical fns.
3. **Migrate the records-family** (`records board calendar gallery tree dashboard gantt`) off `--scan-records-impl` (org-map + `jetpacs-org-entry-matches-p` at point, L480) **onto** `jetpacs-org-vulpea-query` with the view's source. This needs **ID adoption** (`jetpacs-crud-vulpea-ensure-source`): vulpea only indexes file-level notes + headings carrying `:ID:`, so before querying, adopt `:ID:` on the file-level node (`org-id-get-create` at `point-min`) + the source heading + the direct-child record headings, `save` iff modified, `vulpea-db-update-file`; declared sources only; no-op when ids exist. Document in FORMAT.md ("what the runtime does to your files").
4. **Unify** the record shape `(:id :fields)` (keep `:pos` for table/checklist until Phase 3); **merge** `--record-card`/`--note-card` (differences: notes use `:id` + `crud.note.*` actions, records use `:pos` + `crud.*`; unify on `:id` once records carry ids). **Ref fields** (`--ref-resolve` L264 notes branch) → `vulpea-db-get-by-id` then read the display field via `--note-field` (canonical accessors), not an O(n) scan.
5. **Degradation:** factor `jetpacs-crud--needs-vulpea-body` (macro; the `--notes-body` L1368 guard: vulpea-absent → `jetpacs-lazy-column`+`jetpacs-empty-state "needs vulpea"`) and a handler-side `--needs-vulpea` guard (the `(unless (jetpacs-crud--vulpea-p) (user-error …))` at L2641/2742). Apply to EVERY heading kind so the bundle still loads on bare core.
6. **Re-evaluate `jetpacs-crud-vulpea.el`:** the pasted plan wanted a new file for the vulpea logic. Since that logic is now CANONICAL, the new file may be unnecessary — the small composer-side remnants (the `--vulpea-p` probe [L1052-1066 + declare-functions L1037-1050], `--ensure-source`, `--note-field`, the mutate wrapper, `--needs-vulpea-body`) can stay in `jetpacs-crud.el`. If a file is still added, update inline order in `build-app-bundle.el` (L47-49) + `BundleExporter.kt`.

## Verification (per side)

- **jetpacs:** `wsl -d Debian -- sh test/run-tests.sh` (add the note-matcher + entry-matcher tests). Confirm the org-free-core guard (`core-load-test.el`) still passes (core loads with no vulpea, no app).
- **composer:** `VULPEA_DIR=~/pkb/resources/emacs/vulpea sh elisp/test/run-tests.sh` → green (ERT + pantry & hello-world smokes). Smoke runs the bundle on **bare core** (degradation must hold). Add `jetpacs-crud-tests--with-vulpea` (temp vault + isolated `vulpea-db-location`, from the notes round-trip test) and port records/board/gantt/dashboard tests onto it; a degradation loop over all heading kinds.
- **Kotlin (Phase 4, out of scope here):** pre-existing JVM test failures are documented (`63ccb7f`).

## Key facts / gotchas (so the fresh chat doesn't relearn them)

- Composer already uses the binding layer: `jetpacs-defsource "crud.records"`/`"crud.notes"` + `jetpacs-source-query` (L1242/1269). `--scan-records`/`--scan-notes` are thin wrappers over those.
- `--view-source` (L65): inline→`(spec:file . view:title)`; `:file`→`(file . nil)`; `:file+:heading`→`(file . heading)`; `:dir` is invisible here (notes path only, via `--notes-query`).
- `--note-field` (L1075) map: `ITEM`→title, `TODO`→todo, `TAGS`→space-joined tags, `PRIORITY`→char→string, `SCHEDULED`/`DEADLINE`→raw stamp, else `(assoc-string PROP props t)`.
- Ref has two paths: **pos** (`--resolve`, table; buffer offset on the wire, file from spec never wire) vs **id** (`--note-resolve` → `vulpea-db-get-by-id` + scope check `--note-in-source-p`). Callers branch on `(alist-get 'id args)` present (L2014, L2470).
- Vulpea `priority` = char code (65=A); org urgency A>B>C ⇒ smaller char = higher (comparator flips). `scheduled`/`deadline` = raw org timestamps. `regexp` over the index = title+props only (no body).
- Owner develops in this repo concurrently — expect mid-edit/unbalanced states; `check-parens` before running; the pause-and-verify protocol from this session applies.
- `#+JETPACS_APP_FORMAT` gate is now forward-compat (`> current`, current="3"); the unsupported-format fixture is at "4".

## Reference
- Pasted master plan: `docs/PLAN-…` (Phase 0 landed / "vulpea rearchitecture"). This doc supersedes its Phase 2.
- Canonical: `jetpacs/emacs/core/jetpacs-org.el`. Vulpea: `~/pkb/resources/emacs/vulpea` + `docs/comparison.org`/`plugin-guide.org`.
- Cross-project context lives in agent memory: `sdui-rich-server-not-wire-dsl`, `emacs-ecosystem-as-jetpacs-engines`, `jetpacs-glasspane-repo-split`.
- Runtime maps generated this session (session scratchpad — regenerate by re-reading if gone): `crud-maps/{scanners,filter,bodies_cards,ref_mutate}.md`; vulpea API maps `\\wsl.localhost\debian\home\calebc42\.cache\vulpea-maps\{query_api,note_meta,extractor_schema,db_sync,interactive_ecosystem}.md`.
