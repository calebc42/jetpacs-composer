# Plan: Phase 5 — module split, dead-code sweep, docs

**STATUS (2026-07-13): not started; scope SHRUNK by the canonical
consolidation.** Phases 0–4 of the vulpea rearchitecture
([PLAN-vulpea-rearchitecture.md](PLAN-vulpea-rearchitecture.md)) have
landed. Since this doc was written, the note-index FILTER matcher moved
into the canonical core (jetpacs api 1.6.0, submodule `5c84a68` — see
[PLAN-phase2-canonical-jetpacs-org.md](PLAN-phase2-canonical-jetpacs-org.md)):
`--note-priority-char`, `--note-planning-match`, `--note-done-p`,
`--note-matches-p`, `--index-filter-terms`, `--filter-index-supported-p`
**no longer exist in `jetpacs-crud.el`** — strike them from §1's move
list. What remains of §1 is the probe, ensure-source/reindex, the thin
`--compile-filter` router, `--query-view-notes` (now a thin adapter over
`jetpacs-org-vulpea-source-notes`), `--note-field`, and the extractor +
readers; whether that still justifies a separate file is an open call
(§1's "re-evaluate" note in the canonical plan). §§2–4 stand as written.
This doc is the executable handoff for the remaining cleanup.

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

1. **Table/checklist logical addressing.** Today cell/checkbox mutations
   address by a `pos` hint from the fresh index (reindex-on-write keeps it
   current), which is robust between renders but not against an external
   edit landing between a render and a tap. The plan's full design carries
   `(view, tbl-index, heading, row, col)` and re-locates via
   `jetpacs-crud--locate-table` + `org-table-goto-line/column`, with the
   checklist verifying `(ordinal, text)`. Wire shape would change →
   regenerate goldens. Records already have the analogous id-hardening
   (`jetpacs-crud--resolve-id-pos`); this is the table equivalent.

2. **On-device smoke (Phase 4 leftover — needs hardware).** Documented
   procedure for a fresh device: composer "Setup device" (the Phase-1
   `Deployer` bootstrap installs vulpea/org-ql + autosync) → staging deploy
   hello-world → verify every kind renders, a cell edit round-trips, and an
   external Termux `sed` edit + autosync refreshes the view. Add to README.

3. **Three pre-existing JVM `OrgCodecTest` failures** (red before this
   work): `writerEmitsParsableCanonicalForm` and
   `formatTwoIsTheCleanCutoverVersion` still assume the format-2 era (the
   writer emits `3`; old formats are accepted per FORMAT.md — the tests are
   stale), and `sharedParserParityManifest` hits a JVM-vs-elisp divergence
   on unknown-`:KIND:` leniency (the elisp parser accepts an unknown kind
   with a warning; the JVM parser demands a `:SCHEMA:` and rejects it —
   make `OrgCodec` lenient to match, and drop/rewrite the two stale format
   tests). This is a small parser-parity fix plus a decision on whether old
   `#+JETPACS_APP_FORMAT:` versions should be rejected outright.

## Key files
- `elisp/jetpacs-crud.el` (source of the extraction; dead-code sweep)
- `elisp/jetpacs-crud-vulpea.el` (new)
- `elisp/jetpacs-crud-orgapp.el`, `elisp/build-app-bundle.el`,
  `elisp/test/crud-tests.el` (require/parts wiring)
- `src/main/kotlin/com/calebc42/composer/export/BundleExporter.kt` +
  `src/test/kotlin/com/calebc42/composer/export/BundleExporterTest.kt`
- `docs/FORMAT.md`
- `src/test/kotlin/com/calebc42/composer/org/OrgCodecTest.kt` (deferred #3)
