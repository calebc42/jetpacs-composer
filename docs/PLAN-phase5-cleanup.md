# Plan: Phase 5 — module split, dead-code sweep, docs

**STATUS (2026-07-13): EXECUTED (§§1–4).** The split WAS still justified
after the canonical consolidation shrank it: `jetpacs-crud-vulpea.el`
now owns the engine layer (~470 lines — both probes, `:ID:` adoption +
`--reindex`, the index reads, the FILTER router, the tables+checklist
extractor + DB readers) and `jetpacs-crud.el` (~2620 lines) keeps
rendering/actions/sources with declare-function stubs as the seam; the
engine file requires `jetpacs-crud` (safe, never required back); the
bundle inlines three parts (`build-app-bundle.el` +
`BundleExporter.kt`/gradle include/exporter test). §2 also caught a
bonus: two shadowing `--slug` definitions (the live one kept). §3's
FORMAT.md pass landed (unified FILTER subset table, per-kind
degradation matrix, TODO/DONE approximation, and the stale
"positions never stale" preamble replaced by the real addressing
contract). §4 guardrails all green: 74/74 + both smokes with bare-core
degradation, byte-compile clean, BundleExporterTest, submodule core
guard. **The three deferred items below remain deferred** (logical
addressing, on-device smoke, the 3 red JVM `OrgCodecTest` cases).
The original handoff follows as the design record.

**Baseline to preserve:** `VULPEA_DIR=~/pkb/resources/emacs/vulpea sh
elisp/test/run-tests.sh` → **74/74 ERT + pantry & hello-world bundle
smokes green**. Keep it green after every step. Run under WSL Debian
(the Bash tool maps to a different distro; use `wsl -d Debian -- sh -c`
from PowerShell, or run in WSL directly). Repo: `C:\Users\caleb\AndroidStudioProjects\jetpacs-composer`.

---

## 1. Module split → `elisp/jetpacs-crud-vulpea.el`

Right now all the vulpea code lives in `elisp/jetpacs-crud.el`. Extract it
into a new `elisp/jetpacs-crud-vulpea.el`. This is the largest task;
**do it in one commit and lean on the full test suite** — it is a pure
move, so any breakage is a load-order or missing-`declare-function` issue.

### What to move (grep these out of `jetpacs-crud.el`)
- **Probe:** `jetpacs-crud--vulpea` (defvar), `jetpacs-crud--vulpea-p`,
  the `jetpacs-shell-refresh-hook` reset. (Note `--vulpea-p` calls
  `jetpacs-crud-vulpea--register-extractor` — keep them together.)
- **Write/index:** `jetpacs-crud-vulpea-ensure-source`, `jetpacs-crud--reindex`.
- **FILTER compiler + note matcher:** `jetpacs-crud--note-priority-char`,
  `--note-planning-match`, `--note-done-p`, `--note-matches-p`,
  `--index-filter-terms`, `--filter-index-supported-p`, `--compile-filter`.
- **Unified read:** `jetpacs-crud--query-view-notes`, `--view-notes-records`,
  `--notes-org-ql-ids`, `--note-field`, `--slug` (used by note file names).
- **Extractor + readers:** everything under the "Tables & checklists: the
  vulpea extractor" header — `jetpacs-crud-vulpea--extractor-version/-registered`,
  `--ancestor-heading`, `--plain`, `--cell-text`, `--table-rows`, `--item-text`,
  `--encode-rows`, `--decode-rows`, `--extract`, `--register-extractor`,
  `--file-note-id`, `jetpacs-crud-vulpea-tables`, `jetpacs-crud-vulpea-checklist`.
- All the `(declare-function vulpea-* …)` and `(declare-function emacsql …)` stubs.

### Load order (the load-bearing decision)
`jetpacs-crud.el` bodies **call** the moved functions (`--query-view-notes`,
`--source-table` → readers, `--build-body` → `--vulpea-p`, mutations →
`--reindex`), and the moved code **calls back** into `jetpacs-crud.el`
helpers (`--view-source`, `--schema-props`, `--field-type`, `--with-source`,
`--goto-heading`, `--scan-table` in the extractor? no — extractor is AST-only).
So they are mutually recursive at the function level.

Resolution (functions resolve at call time, not load time):
- New file `(require 'jetpacs-crud)` at top? **No** — that would be circular
  if `jetpacs-crud.el` requires the vulpea file. Instead:
  - `jetpacs-crud.el`: add `(declare-function jetpacs-crud--query-view-notes …)`
    etc. for the moved functions it calls, and **do not** require the vulpea
    file (the bundle and `jetpacs-crud-orgapp.el`/tests load both).
  - `jetpacs-crud-vulpea.el`: `(require 'cl-lib)`, `(require 'org)`,
    `(require 'org-element)`; **no** `(require 'jetpacs-crud)` (avoid the
    cycle) — add `declare-function` stubs for the `jetpacs-crud.el` helpers
    it calls; end with `(provide 'jetpacs-crud-vulpea)`.
  - Whoever loads the runtime must load **both**. Update:
    - `elisp/jetpacs-crud-orgapp.el`: it `(require 'jetpacs-crud)` — add
      `(require 'jetpacs-crud-vulpea)`.
    - `elisp/test/crud-tests.el`: add `(require 'jetpacs-crud-vulpea)`.
    - `elisp/build-app-bundle.el`: the `parts` list is
      `'("jetpacs-crud.el" "jetpacs-crud-orgapp.el")` — insert
      `"jetpacs-crud-vulpea.el"` **between** them (so the runtime, then
      the vulpea layer, then the orgapp front-end). Registration side
      effects (`--register-extractor` via `--vulpea-p`) are lazy, so order
      among the three only matters for `provide`/`require`, which is satisfied.
- **Registration timing:** `--register-extractor` runs on first `--vulpea-p`.
  After the split, confirm the extractor still registers before the first
  `vulpea-db-update-file` in `ensure-source` (it calls `--vulpea-p` first).

### Kotlin parity pin
`src/main/kotlin/com/calebc42/composer/export/BundleExporter.kt` mirrors
`build-app-bundle.el`'s inline. Update it to the **three-part** list and
extend the CI equivalence test (`BundleExporterTest.kt`) so the desktop
exporter and the reference script stay byte-identical.

### Verify
Full ERT + both smokes green (the move must be behavior-neutral). Then a
`emacs -Q --batch -L jetpacs/emacs/core -l elisp/jetpacs-crud-vulpea.el`
byte-compile check for stray warnings.

---

## 2. Dead-code sweep (`jetpacs-crud.el`)

Confirmed dead after the rewrite — delete:
- `jetpacs-crud--query-supported-p` — was only used by the deleted
  `--scan-records-impl` org path.
- `jetpacs-crud--org-ql-positions` — same.

**Keep (still used):** `--scan-table`, `--find-table`, `--scope-bounds`
(mutation-time live reads in `row-add`, `--locate-table`, `item-add`);
`--scan-checklist` (`item-add` finds the last item); `--goto-heading`;
`--org-ql-p`/`--org-ql` (used by `--notes-org-ql-ids`); `--note-field`.

Sweep stale `declare-function` stubs and confirm nothing references the
deleted two. `grep -n 'query-supported-p\|org-ql-positions'` must be empty
after.

---

## 3. FORMAT.md consolidation

Incremental edits already landed (id-adoption contract, per-kind
degradation, `#+JETPACS_DEPENDS:`, device setup). Do a consolidated pass:
- A **FILTER subset table** — the terms the index evaluates
  (`todo done tags priority heading regexp property level scheduled
  deadline`) vs. the org-ql extension for anything beyond, identical for
  records and notes now (Phase 4 unified them).
- A **per-kind degradation matrix** (every kind → "needs vulpea"
  placeholder on bare core).
- Note the **per-file TODO/DONE approximation**: `done`/bare-`todo`
  resolve done-ness against global `org-done-keywords` (fallback `"DONE"`)
  + the `closed` slot; exotic per-file done keywords need the org-ql arm.
  (See `jetpacs-crud--note-done-p`.)

---

## 4. Guardrails to re-check
- The jetpacs submodule's **org-free-core guard** (`test/core-load-test.el`
  in the submodule) must still pass — the composer runtime must not leak
  org requirements into `jetpacs-core`. Run it after the split.
- `git status` should show the `jetpacs` submodule only as the committed
  pointer `40d4972` plus its local (not-ours) `gradle.properties` tweak.

---

## Deferred items (decide whether to pull into Phase 5 or leave)

1. ~~**Table/checklist logical addressing.**~~ **RESOLVED 2026-07-13.**
   Cells carry org-table's own `(line, col)` (header = line 1) and are
   re-located live at mutation time (`jetpacs-crud--table-cell-pos` over
   `--locate-table`; the view renders the first table in scope, so no
   tbl-index is needed); checklist toggles carry `(idx, text)` verified
   by `--checklist-item-pos` (pos fast-path → idx+text re-scan → unique
   text match → clean "Item moved — pull to refresh"). Index positions
   ride as hints only. Goldens regenerated (diff = arg shape only);
   four staleness tests pin relocation and the clean-error paths.

2. **On-device smoke (Phase 4 leftover — needs hardware).** Documented
   procedure for a fresh device: composer "Setup device" (the Phase-1
   `Deployer` bootstrap installs vulpea/org-ql + autosync) → staging deploy
   hello-world → verify every kind renders, a cell edit round-trips, and an
   external Termux `sed` edit + autosync refreshes the view. Add to README.

3. ~~**Three pre-existing JVM `OrgCodecTest` failures**~~ **RESOLVED
   2026-07-13.** The decision went to parity-with-the-elisp-oracle
   (which FORMAT.md already documented): old `#+JETPACS_APP_FORMAT:`
   versions are ACCEPTED, only a future version is rejected. Fixes:
   `OrgCodec`'s schema demand no longer covers `ViewKind.UNKNOWN`
   (mirrors the elisp `memq` list — forward-compat leniency accepts an
   unknown `:KIND:` and surfaces it as Unknown);
   `writerEmitsParsableCanonicalForm` pins the writer to
   `OrgCodec.FORMAT_VERSION` instead of a literal `2`; the stale
   `formatTwoIsTheCleanCutoverVersion` became
   `formatGateAcceptsOldAndRejectsFuture`. Full JVM suite green
   (26/26); shared parity manifest passes on both sides.

## Key files
- `elisp/jetpacs-crud.el` (source of the extraction; dead-code sweep)
- `elisp/jetpacs-crud-vulpea.el` (new)
- `elisp/jetpacs-crud-orgapp.el`, `elisp/build-app-bundle.el`,
  `elisp/test/crud-tests.el` (require/parts wiring)
- `src/main/kotlin/com/calebc42/composer/export/BundleExporter.kt` +
  `src/test/kotlin/com/calebc42/composer/export/BundleExporterTest.kt`
- `docs/FORMAT.md`
- `src/test/kotlin/com/calebc42/composer/org/OrgCodecTest.kt` (deferred #3)
