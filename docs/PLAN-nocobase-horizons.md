# Plan: horizons from NocoBase — the roadmap past v1

**STATUS (2026-07-12): active implementation.** Tier 0, Tier A, and the
composer-owned Tier B surface have landed on FORMAT 2; the remaining B5/B6/B9
native pieces are explicitly tracked as Jetpacs framework prerequisites. This remains a wide-horizon ranking of
where jetpacs-composer goes after the original v1 format
([FORMAT.md](FORMAT.md)), mined from [NocoBase](https://github.com/nocobase/nocobase)
— a mature open-source no-code platform — for design concepts that
survive translation to an org-native, single-user, phone target. Every
item was argued against the eight original constraints and reshaped to
fit or cut. Each larger item may still graduate to its own plan doc / PR.
This is the composer-side companion to
jetpacs' [PLAN-next-primitives.md](../../jetpacs/docs/PLAN-next-primitives.md);
the framework asks it implies are collected under **The jetpacs
frontier** below.

## Why

The audit behind this doc found composer's Kotlin model already
*enumerates* a mature feature set that is **phantom** — modeled,
editor-exposed, and round-tripped by `OrgCodec`, yet dead or broken on
the device:

- `ViewKind.BOARD / CALENDAR / GALLERY` (`AppSpec.kt:38`) — parsed and
  emitted, but `CALENDAR` renders a group-by-date card list whose own
  comment falsely claims no native month grid exists (`crud.el:643`),
  and `BOARD` is an unordered one-lane-per-value bucket.
- `ColType.Ref(targetView)` (`AppSpec.kt:125`) — pickable in
  `ViewForm.kt:703`, written by `OrgCodec.kt:221`, and **rejected by the
  device parser** (`jetpacs-crud-orgapp.el:66`), so a ref-bearing app
  fails `parse-app` on-device and is silently skipped
  (`load-directory:271`).
- The whole `ActionDef` vocabulary (`AppSpec.kt:169`, `ActionEditor.kt`,
  `OrgCodec.parseActions`) — a complete editor + codec round-trip that
  **nothing in the runtime consumes**, and that `FORMAT.md` never even
  documents.

NocoBase is the reference because it has already walked this road: its
field-interface split, block/view taxonomy, relation interfaces, and
no-code action/automation model are the accumulated answers to "what
does a CRUD-app builder need next." The recurring finding is that the
best wins are **cheap** — the org primitive (`#+TBLFM`, allowed-values,
planning lines, the outline-as-tree) or the jetpacs widget
(`month_grid`, `enum_list`, `reorderable_list`, `text_input`) already
exists and composer simply isn't using it.

## The constraints, restated (every item cites its tension)

1. **No code in `app.org`** — the document is declarative; logic lives
   in the versioned runtime.
2. **Bundle loads on a bare `jetpacs-core`** — optional deps (`org-ql`,
   `vulpea`) are soft-depended, never vendored; a view degrades to a
   placeholder when its dep is absent.
3. **Org files are the backend** — human-readable, Emacs-editable,
   git/Syncthing-syncable.
4. **Closed `crud.*` action vocabulary** — every handler resolves its
   file from the spec (`--resolve`, `crud.el:968`), never from the wire.
5. **Target is a phone** — Emacs-on-device is the compute; *the composer
   itself is a Compose **Desktop** app*, so desktop idioms (drag, Ctrl+Z)
   are correct there.
6. **The composer is a convenience, not a gatekeeper** — `app.org` stays
   hand-authorable and round-trips losslessly (opaque bodies verbatim).
7. **New keyword / kind / coltype / action ⇒ deliberate format decision.**
   The current clean-cut version is `2`; there is no installed v1 compatibility
   ladder. Current non-goals include formulas, conditionals, arbitrary layout,
   per-node styling, and cross-app data-source references.
8. **Core org does the heavy lifting** — `TODO/DEADLINE/PRIORITY/ITEM`
   are first-class via org's own machinery; enums from `*_ALL`;
   filtering speaks org-ql's grammar.

## Tier 0 — correctness debt (before any feature)

The format froze at `1` while both parsers grew `board/calendar/gallery`,
`GROUP_BY/DATE_FIELD/IMAGE_FIELD`, `#+TODO/#+TAGS`, and `:ACTIONS:` — a
live violation of its own freeze (constraint 7), and the parsers
genuinely diverge enough to **ship apps that never appear on the phone**.

- **Resolve the phantom divergence** — [high / S]. Decide `ColType.Ref`
  (gate/remove it from the composer until the runtime backs it — see B2)
  and `EFFORT/CATEGORY` (add to `orgapp.el` special-props, or drop from
  `AppSpec.kt:55`). Until then, add a `ModelOps.validate` problem +
  export gate that **blocks emitting a bundle the device will reject**.
- **Parser parity by test, not codegen** — [high / S]. One shared
  accept|reject fixture listing every keyword/kind/coltype/special-prop/
  action, run against *both* the elisp ERT and JVM `OrgCodec`, failing CI
  on divergence. Extends the repo's existing shared-fixture approach
  (`OrgCodec.kt:22`); defer NocoBase's manifest-drives-N-targets codegen
  until the format is big enough to earn it.
- **Severity-tag composer problems** — [high / S]. Add
  `Severity{Error,Warning,Info}` to `ModelOps.Problem` (today
  `EditorScreen.kt:122` dumps everything red) and the drift checks that
  catch the bugs above: ref-target-not-a-live-view, `groupBy`/`dateField`/
  `imageField` not a schema field, action keyword not in `todoSequence`,
  `EFFORT/CATEGORY` case drift.

## The FORMAT-2 gate (a shared, one-time bump)

Most of Tier B needs new keywords / kinds / verbs, i.e. one
`#+JETPACS_APP_FORMAT` `1 → 2` bump. There is no installed v1 app base,
so the accepted policy is a **clean cutover**, not compatibility machinery:

- Both parsers accept the current version (`2`) and reject an explicit `1`;
  missing means current for hand-authored convenience. The canonical writer,
  fixtures, templates, and Hello World always emit `2`. There is no migration
  ladder and no `format ≤ max` fallback.
- **Forward-degrade** (the one genuinely novel idea worth adopting
  early): relax the runtime's hard error on an unknown `:KIND:` / coltype
  (`orgapp.el:134`) into a **placeholder view**, mirroring the vulpea
  soft-dep (`crud.el:705`), so a newer doc degrades gracefully on an
  older runtime instead of the whole app being skipped. This is what
  makes a versioned format safe to grow.

Reconcile `FORMAT.md` to describe what `2` actually contains as part of
the bump. A future breaking change can introduce migration policy if an
installed base exists then.

## Tier A — near-term features (landed before the FORMAT-2 cutover)

Everything here is elisp-runtime and/or composer-desktop only: no new
format surface, no bundle-compat risk.

### A1. Calendar → the native `month_grid` — [high / **S**]

The cheapest high-value item in the plan. Rewrite `jetpacs-crud--calendar-body`
(`crud.el:643`) to build a `marks` alist bucketing records on the
*existing* `DATE_FIELD` (already round-trips both parsers;
`ViewForm.kt:449` already ships the picker) and hand it to
`jetpacs-month-grid` (`widgets.el:426`), wrapped
`(if (jetpacs-node-supported-p 'month_grid) … <current card list>)` so
the card list becomes the declared fallback (constraint 2). Normalize
org timestamps → `YYYY-MM-DD` first; precompute every month present
(single-user org files are small, avoiding `on_month_change`
round-trips). **One runtime function, zero parser edits, no format bump,
no new action.** From NocoBase `plugin-calendar`. Delete the false
comment. `COLOR_FIELD` tint, `END_DATE` spans, and a day-tap drill-down
are a separate FORMAT-2 parity batch; a week/day toggle is a *new native
widget* (the frontier), not composer scope.

### A2. Board → a real kanban — [high / M]

From `plugin-kanban`, reusing the existing FORMAT-2 vocabulary:
1. Constrain the composer `groupBy` picker to enum / `TODO` fields
   (`ViewForm.kt:435`) — stops free-text group-by producing a junk
   one-lane-per-value board.
2. Seed **ordered, non-empty lanes** from org's own allowed-values —
   `jetpacs-crud--allowed-values` already returns the `#+TODO` sequence /
   `PROP_ALL` in order (`crud.el:1151`) — before bucketing in
   `--board-body` (`crud.el:601`). No new drawer key (drop `GROUP_VALUES`
   unless a *custom* lane order is later wanted; that alone is FORMAT-2).
3. The move affordance is the **existing** `crud.field.edit` on the
   group-by prop, surfaced inline as the currently-unused
   `jetpacs-enum-list` segmented control (`widgets.el:532`, renderer
   confirmed) — a genuine phone-UX upgrade over the minibuffer, no new
   action. The real gap is only the default `TODO` board, where `TODO` is
   folded into the card title and excluded from tappable rows
   (`crud.el:560`).

Optional tail (M): per-lane quick-create — a per-column add affordance
plus an optional schema-validated `seed` alist in the `crud.record.add`
*wire args* (rides the wire, not `app.org`, so not a format change).
True cross-lane drag is out of scope — no framework primitive exists
(`reorderable_list` is single-list vertical); the enum_list/menu move is
the honest interim.

### A3. Record quick-clone / duplicate — [high / **S**]

The standout cheap data-entry shortcut, absent from the vocab. From
`plugin-action-duplicate`: `org-copy-subtree` + `org-paste-subtree`
adjacent to the original, regenerate volatile fields (fresh `:ID:` via
`org-id-get-create`, optionally clear `TODO` + timestamps); table views
copy the source row's cells. One entry beside *Delete* in
`crud.record.menu` (`crud.el:1297`) / `crud.row.menu`. Drop NocoBase's
"copy-into-form" and field-subset-toggle variants — that is a mini
form-prefill UI, not a quick-clone. *(New verb ⇒ FORMAT-2; if bundling
with A-tier is undesirable, this can wait for the B batch, but it is
cheap enough to justify its own bump.)*

### A4. Runtime keyword search — [high / M, no format bump]

From `plugin-multi-keyword-filter`. The `:FILTER:` is parsed and matched
on *every* scan (`crud.el:512` records, `:788` notes), so AND a transient
runtime query tree with the static one at those two sites. Build the
query by **composing existing matcher terms** —
`(or (regexp k1) (regexp k2) … (tags k1 k2 …))` — no new grammar, no
`--entry-matches-p` change. The query string lives in `jetpacs-ui-state`
(API-stable, survives pushes); render it as the *already-rendered*
`jetpacs-text-input` with `on-submit` (prefer submit over change so
on-device Emacs doesn't re-scan per keystroke) via a single new
`crud.view.search` handler. Records / notes / board only — table search
stays a v1 non-goal. No new node needed (the deferred scaffold
`search` slot in the frontier is pure animation polish, not a
prerequisite).

### A5. A composed form sheet for record-add — [high / M]

Today record-add is a **one-blocking-dialog-per-field loop**
(`crud.el:1256`) — brutal on a phone. Replace it with a `jetpacs-column`
of the typed widgets composer already ships but never uses (`enum_list`
for enum/`PROP_ALL`, `date_button` for dates, `text_input :keyboard
number`), one submit firing the existing `crud.record.add` (whose handler
already reads each field from `ui-state`). **No new node, action, kind,
or format bump** — the widgets already auto-publish `state.changed`; the
only gap is `date_button`, which delivers via injected value and needs a
tiny state-sink. Draft autosave (from `plugin-form-drafts`) is near-free:
stop clearing `ui-state` on cancel, show a Restore/Discard banner, clear
on successful add — a strict win for constraint 3 (unsent input never
touches the versioned org file). Reuse the same sheet for whole-record
edit (→ L). Defer inline per-field validation: there is no error-slot
substrate yet (a frontier item).

### A6. A guided filter builder — [high / M]

Replace the raw org-ql textarea (`ExpressionDialog.kt`), whose help
advertises the *full* grammar even though the interpreter implements only
~13 terms and **org-ql is never actually dispatched** — so an author can
save a filter that parses but throws on-device. Offer a term palette
pinned to what `--entry-matches-p` (`crud.el:203`) and the narrower
`--note-matches-p` (`crud.el:758`) actually implement, per-kind gated,
autocompleting property names from `view.schema`, keywords from
`spec.todoSequence`, tags from `spec.tags` (all already in session
state). **Keep a raw-sexp fallback mode** (constraint 6: hand-authored
nested filters, or a richer term on an org-ql-equipped device, still
round-trip). The load-bearing cost is a Kotlin parse/serialize/validate
layer mirroring `--parse-query`'s three input shapes; ship it and the
palette off one declared term table. From NocoBase's typed
filter-operator catalog. Fix the misleading "org-ql" copy at
`ViewForm.kt:397`.

### A7. Direct-manipulation editing — drag-reorder + undo/redo — [high / L, unbundle]

All pure Kotlin (`ModelOps` / `EditorSession` / Compose); the composer is
a Desktop app, so these idioms are correct (constraint 5 governs the
*runtime*, not the authoring tool).
- **Tier 1 (S):** `ModelOps.moveColumn` / `moveSchemaField` mirroring the
  existing `moveView` arrow-buttons (`EditorScreen.kt:243`). The hazard:
  `:COLTYPES:` is **positional**, so an inline-table move must permute
  header + every row's cells + colTypes in lockstep (`mapFirstTable`,
  `ModelOps.kt:75` is the seam); external tables permute `columns` +
  colTypes; records permute `schema` + colTypes. Assert alignment in
  `ModelOpsTest`. This closes the actual gap at S.
- **Tier 2 (M):** bounded past/future `AppSpec` snapshot stacks on
  `EditorSession`, pushed inside `update()`'s existing `onSuccess`.
  **Coalescing per-keystroke edits is mandatory**, not polish (every
  field calls `update` on each keystroke). Mind two state wrinkles:
  selection (lives in `EditorScreen`, clamp/reset on undo) and `dirty`
  (recompute `spec != savedSpec`).
- **Tier 3:** the actual drag gesture — defer; arrow buttons already give
  full reorder.

From FlowModel `serialize()`/`clone()` + composable submodels; the reason
is a single-user builder genuinely needs to reorder, and the immutable
value-typed `AppSpec` makes it cheap.

## Tier B — the FORMAT-2 batch (new kinds / verbs / keywords)

Ship these together behind the one bump. Ordered by value/fit.

### B1. `tree` view kind — [high / L]

The org outline **is** a free adjacency list. From `plugin-block-tree`.
The first real consumer of the orphaned-but-stable `jetpacs-reorderable-list`
(`widgets.el:284`): a `--define-kind 'tree` whose scan walks the SOURCE
subtree at *all* depths (drop the `1+ base` gate at `crud.el:522` that
hides nesting), emitting items shaped `(label level pos file)` — `pos`
and `file` are required (`ReorderableList.kt:84` keys on `pos_file`).
Add a closed `crud.node.move` doing org-native **marker-based cut +
`org-paste-subtree` at level** (NOT `move-subtree-up/down` + promote,
which can't reparent), guarding the self-drop case to avoid losing the
cut subtree (constraint 3). v1 = reorder/reparent only; tap opens the
record, FAB adds a top-level node.

### B2. Wire `ColType.Ref` — within-app many-to-one with drill-in — [high / L]

**First cut landed:** same-app records/notes target validation, composer target
and display-field pickers, target-record selection, title resolution, and
ID-addressed drill-in. The richer framework-level record-picker input remains
an optional F2 follow-up, not a blocker for this runtime-backed first cut.

From NocoBase m2o interfaces. Store the FK as the target record's org
`:ID:` (rename-proof, human-readable, reuses `:ID:` addressing at
`crud.el:1316`); a `--ref-resolve` looks up the target view *in the same
spec* and returns its title field, threaded through `--cell-display`
(`crud.el:356`) and `--field-type` (`crud.el:495`), degrading to the raw
id when the target's source is absent. Render as a tappable chip opening
the target in a **detail overlay** (pairs with B6). This *is* a
cross-source reference (a v1 non-goal), so the format bump is
load-bearing; the security boundary (constraint 4) is preserved **only
if** the drill-in addresses the target by `(app, target-view, key)`
resolved server-side — never a file path over the wire — with a
read/write split: reads may touch any same-app source, **writes stay
view-bound**. Replace the free-text ref-target box (`ViewForm.kt:724`)
with a view-name dropdown + display-field picker + `ModelOps.validate`
checks. First cut = records/notes targets (they already address by
`:ID:`); table targets need an explicit key-column — defer. **Cut** the
CRM tail: computed o2m backlink scans (O(source) per detail open) and
import ref-inference (misproposes on hand-authored org).

### B3. Revive the `ActionDef` vocabulary — [high / L]

**Landed:** records and notes expose the same configured menu/swipe actions;
all writes dispatch through the closed interpreter and resolve their source
server-side from either a position or stable note ID.

Resurrect the phantom `:ACTIONS:` drawer as one closed `crud.action--apply(file,
pos, token)` interpreter over the **7 existing tokens** (set-todo,
schedule, deadline, tags, priority, refile, archive) — each maps to an
org primitive already in the file (`org-todo`, `org-schedule/deadline`,
`org-set-tags`, `org-priority`, `org-refile`, `org-archive-subtree`).
Consume the already-parsed `:actions` raw string (`orgapp.el:184`) in the
records/notes body builders to emit each card's *existing* swipe-start /
swipe-end + a menu entry (satisfies the "swipe must also be reachable by
tap/menu" invariant). Keep enumerated value tokens (`@today`/`@now`/
`@empty`) a **fixed closed enum**, never an expression grammar — that is
what keeps the no-formulas non-goal intact. From NocoBase's
`assignFieldValues` reusable step. (Multi-select bulk fan-out is a
separate frontier + review; see F4.)

### B4. Bulk data I/O — CSV import / export / share — [high / export M, import L]

**Landed:** table and record-like views can copy spreadsheet-safe CSV, copy
org-table text, or explicitly share CSV. Table views can paste-import CSV with
exact-header and typed per-row validation before one atomic append; records and
notes import remain deliberately out of scope.

From `plugin-action-export` / `-import`. **Export** (M) renders a table /
records view to CSV (header = schema) or org-table text via *core*
effectors — `clipboard.copy` (`jetpacs-widgets.el:342`), `intent.start
ACTION_SEND` text/plain (`jetpacs-device.el:35`), or a `/sdcard/Download`
write — doubling as "print/share view". Note export is the runtime's
**first write outside the declared source set**, so gate it deliberately
(user-initiated, explicit destination, no wire-supplied path). Keep
NocoBase's CSV formula-injection escape (`/^\s*[=+\-@]/`) for
downstream-spreadsheet safety. **Import** (L, riskiest, last) scopes v1
to TABLE views where the CSV↔org-table symmetry holds: coerce each cell
against `:COLTYPES:`, append through the existing `--table-mutate` path,
report the first bad row's index (NocoBase's per-row-error idea).
Defer CSV→records/notes (subtree synthesis).

### B5. Org-native DATE_FIELD reminders — [high / L]

**Declarative layer landed, framework-gated:** composer/parser validation,
core-org date derivation, stable reminder IDs, and load/after-push syncing are
implemented behind `jetpacs-reminders-owner-set`. Until the owner-merge
framework seam lands, the runtime warns and deliberately sends no global
replace-set, preserving other apps' alarms.

From NocoBase's `DateFieldScheduleTrigger`. A view carries a declarative
`:ON:` rule (`:REL: -3d :DATEFIELD: DEADLINE`). On load and after each
push, the runtime scans the source's `DEADLINE`/`SCHEDULED` (via **core
org, not org-ql** — works on a bare core) and arms one durable
`reminders.set` per record at `date + offset` (SPEC §7: fires with Emacs
dead — do *not* route through a wake time-trigger that re-scans
per-record). **Cut** the NocoBase-flavored parts that fight the grain:
STATIC cron (thin value, least org-native), the condition step (brushes
the no-conditionals non-goal), and delay-N-days durable-resume (most
speculative). Requires a framework prerequisite — trigger owner-scoping
(F6) — so a redeploy doesn't strand the previous build's alarms, and an
owner-merge layer for the device-global reminder set.

### B6. Quick-capture to a declared inbox — [high / M]

**In-app capture landed; tile framework-gated:** `#+JETPACS_INBOX`, composer
editing, the spec-scoped append-only action, top-bar affordance, and per-app
default FAB registration are implemented. The off-app `tile:customN` surface
remains blocked on F5 teardown/slot ownership so redeploy cannot strand a tile.

From `plugin-public-forms` (token-scoped append-only writes). A
file-level `#+JETPACS_INBOX: <path>` keyword + a new `crud.capture.add`
verb whose **only** capability is `org-insert-heading` appending one
heading (title + minimal drawer) to that one path — a strictly tighter
boundary than the record verbs (it can never read or edit). Surface it as
a per-app capture FAB via the already-present, unused
`jetpacs-apps-set-default-fab` (`apps.el:197`) and one off-app affordance
(a `tile:customN` whose tap opens the capture form). This is the killer
mobile feature — org-capture-from-anywhere — fire-and-forget, no
staleness, code-free on the consumer side. Depends on the framework
releasing surfaces on teardown (F5).

### B7. Record `detail` overlay — [both / L]

**Landed:** record-card and reference taps resolve into a full-height sheet
using the correct records/notes builder, with every schema field, configured
actions, reference behavior, and the entry's org body prose.

From NocoBase `DetailsBlockModel` + `openView`. A full-record view (all
schema fields, body prose) opened from a card tap or a ref chip (B2),
carrying the `ActionDef` buttons (B3) as its record actions. The natural
home for the "one record, everything about it" surface that a flat card
list can't give.

### B8. Chart `dashboard` view kind — [medium / M]

**Landed:** closed count/sum/avg metrics, optional schema-field grouping,
composer controls, chart-card workbench rendering, and records-list fallback
when the companion does not support the additive chart node.

Aggregate records (count / sum / avg — from `plugin-workflow-aggregate`)
into the existing `jetpacs-chart`, laid out as a workbench of chart
blocks (`plugin-block-workbench`). Gate on `jetpacs-node-supported-p
'chart`; the card list is the fallback.

### B9. `gantt` view kind — [both / L]

**Fallback landed; native rendering framework-gated:** format/composer support,
required org-native fields, deadline/start sorting, range/progress footers, and
normal record actions are implemented. A true horizontal timeline awaits F8.

From `plugin-gantt`. `SCHEDULED` + `DEADLINE` are the start/end; progress
from `TODO`. Needs a **new native timeline primitive** (the frontier);
until then it degrades to a sorted deadline list.

## Desktop preview track — live semantic preview

**Paused after P2:** the shell plus core table/checklist/record/note renderers
remain available, but P3+ is not the current roadmap priority. It adds no
keyword, kind, action, or runtime behavior: it projects the current `AppSpec`
into a desktop Compose phone-like preview that updates with `EditorSession.spec`.
It covers app chrome, every view kind, representative/device-only data states,
details, references, and safe preview-local interactions while remaining
explicitly non-pixel-identical and non-mutating. See
[PLAN-live-semantic-preview.md](PLAN-live-semantic-preview.md) for architecture,
data provenance, phases, acceptance gates, and tests.

## Jetpacs-native abstraction track — vocabulary + bundled Elisp

Jetpacs is Composer's application renderer, not an adjacent implementation:
Emacs loads the generated Tier-1 app into Jetpacs' App Switcher. Glasspane is
the direct-DSL reference application; Composer's job is to make the useful
parts of that public DSL declarative and org-native without exposing arbitrary
code in `app.org`.

Work therefore proceeds compiler-first:

- **V1. Coltype-driven native forms — landed.** The bundled CRUD Elisp now
  compiles record-add fields to Jetpacs' real decimal input, switch, enum-list,
  and date-picker nodes. Widget arrays/booleans normalize back to stable org
  strings, and invalid numbers are rejected before a heading is written.
- **V2. Direct-DSL record vocabulary — landed.** This brings the proven
  Glasspane information hierarchy into `jetpacs-crud`: rich TODO/priority headlines, semantic date
  rows, tag chips, compact captions, and explicit reference/edit affordances.
  These are derived from today's schema and org built-ins; no format keyword is
  needed.
- **V3. Dedicated typed detail/edit composition.** Replace the current
  "record card inside a sheet" with a detail builder using section headers,
  typed values and native field editors while retaining the closed `crud.*`
  action boundary and source-scoped handlers.
- **V4. Vocabulary graduation gate.** Add `AppSpec`/FORMAT vocabulary only when
  direct-DSL evidence shows that data cannot be derived from schema, coltype,
  org properties, or view kind. The field-interface layer remains the likely
  next additive format seam; generic layout/style nodes remain out of scope.

Every slice is tested against the pinned Jetpacs public constructors, linted as
wire data, and exercised through an actual `jetpacs-defapp` registration. The
App Switcher/owner contract is part of acceptance, not preview chrome.

The org parser remains a separate reusable concern. The fork/integration
boundary and shadow-parser cutover are specified in
[PLAN-orgmode-kmp-integration.md](PLAN-orgmode-kmp-integration.md).

## Tier C — deeper structural (as demand appears)

- **Field-interface layer** — [medium / XL]. NocoBase's richest idea
  (`CollectionFieldInterface`): split the stored base *type* from the UI
  *interface* + validation + default + format, with one descriptor
  resolver driving both the org `:COLTYPES:` mapping and the jetpacs input
  widget. High-value long-term direction; the smaller field wins accrete
  toward it. Do not attempt as one project.
- **`#+TBLFM` as a computed-column interface** — [medium / M]. Org's
  native calc engine is the on-device evaluator (nothing vendored). Mark
  formula columns read-only in `--cell-node` (`crud.el:362`); the runtime
  already recalcs `#+TBLFM` inside `--table-mutate`. Fixes a real
  round-trip hazard where `OrgCodec.parseBody` detaches `#+TBLFM` from its
  table (`OrgCodec.kt:301`). From `plugin-field-formula`.
- **Sequence + multi-select interfaces** — [medium / M]. Auto-number
  (`plugin-field-sequence`) and junction-free multi-select tags
  (`plugin-field-m2m-array`), as new field interfaces over org's single
  string storage.
- **Read-only "Relations" panel** — [medium / M]. From
  `plugin-graph-collection-manager`, slimmed: group `ViewSpec` by source
  to surface *"which views edit the same backend file"* (and *"this path
  is typo'd, so they diverge"*) — a real correctness aid the flat outline
  (`EditorScreen.kt:214`) hides. Node x/y in a composer sidecar, never
  `app.org`. **Cut** drag-to-connect-creates-ref (manufactures
  device-unloadable `app.org`) and the relational-DB m2o/o2m glyphs.
- **View-level template library** — [medium / M]. From `ui-templates`:
  save a configured view as a reusable template, insert as a copy —
  beyond the current three org templates.
- **View-kind config from a settings-flow descriptor** — [medium / M].
  From flow-engine's "settings-as-a-flow": generate the per-kind config
  panels from one declarative descriptor with field-type-constrained
  pickers, instead of hand-written `ViewForm` branches.

## The jetpacs frontier — framework changes composer needs (Q2)

Collected asks for the [jetpacs](../../jetpacs) repo, ordered by
leverage. These cross-reference
[PLAN-next-primitives.md](../../jetpacs/docs/PLAN-next-primitives.md)
(the scaffold `search` slot and zoomable-image lightbox already sit in
its Tier B).

- **F1. Fix the `jetpacs-shell-tab-view` forwarding gap** — [**S,
  highest leverage**]. `jetpacs-scaffold` already serializes
  `snackbar_action` + `floating_toolbar` (`widgets.el:784`) and `nav-view`
  already forwards them, but `tab-view` (`shell.el:305`) drops them. Two
  backward-compatible `&key` params **unblock undo snackbars and bulk
  toolbars for every tab-view app at once** — a prerequisite for A-tier
  undo and F4.
- **F5. Quick Tile teardown + slot ownership** — [M].
  `jetpacs-app-unregister` (`apps.el:82`) must release every surface claimed by
  the app, including `tile:customN`, and the framework needs an owner-aware
  allocator for the five custom tile slots so two apps cannot silently claim
  the same slot. Redeploy/uninstall must push an unavailable/empty replacement
  before releasing the claim. Prerequisite for B6's off-app capture tile.
- **F6. Trigger owner-scoping** — [S]. `jetpacs-trigger-register` should
  `(jetpacs--claim "trigger" id)` under the ambient owner and teardown
  iterate `--owned-names` — identical to how actions/views already scope —
  so composer-armed reminders (B5) don't strand on redeploy.
- **F7. Forward-degrade on unknown `:KIND:` / coltype** — [S]. See the
  FORMAT-2 gate above: a placeholder instead of a hard error is what makes
  a growing format safe on older runtimes.
- **F2. Record-picker input node** — [L]. From
  `AssociationFieldMode.Picker`: a dialog over the target view's records
  (`label` = title field, `value` = `:ID:`) with a `completing-read`
  fallback. The input half of relations (B2).
- **F3. Field-input primitives + inline-error surface** — [L]. Rating /
  color / affixed-number nodes and an **error slot** (today validation is
  handler-side `user-error` → snackbar; there is no inline error
  substrate), plus wiring the native date/enum pickers into grid cells.
  Unblocks A5's inline validation.
- **F4. Multi-select / selection primitive** — [L, companion UI].
  `:selectable` / `:selected` on `jetpacs-card` + Jetpack Compose
  rendering, with the selection set in ephemeral `ui-state`. Unblocks
  `crud.action.bulk.*` fan-out over `ActionDef` (B3 phase 2); scope
  destructive-undo-over-N as a real design item, not a freebie.
- **F8. Timeline + calendar week/day widgets** — native primitives for B9 and
  A1's deferred modes. The timeline node should accept stable item IDs, labels,
  start/end epoch values, progress/state, and an ID-addressed tap action; it
  must handle horizontal pan/zoom companion-locally. `month_grid` remains
  month-only, so calendar also needs week/day range nodes rather than composer
  attempting canvas-based interaction.
- Jetpacs framework still lacks jetpacs-reminders-owner-set. Composer
  therefore warns and arms nothing instead of issuing a global reminders.set
  that could erase alarms belonging to other apps.

## Rejects (and what serves instead)

- **ACL / RBAC and i18n** — assessed and rejected for this target. The
  `:SOURCE:` boundary is the only capability model a single-user phone
  needs; roles / field-permissions / data-scopes are enterprise-SaaS
  weight. i18n waits for a second locale actually being used.
- **A full workflow / job-queue engine** (on-record-change rules, a
  resumable processor) — XL, Turing-adjacent, and app-level rule
  automation is *glasspane territory* (rules read from org files), not the
  composer's single-user v1. B5's DATE_FIELD reminders are the org-native
  slice worth keeping.
- **Named cross-app data-source registry** (`DataSourceManager`) — low
  value when a user runs a few apps; the within-app same-source insight
  (the Relations panel) delivers the useful part.
- **The ref CRM tail** — computed o2m backlink scans and import
  ref-inference; scope and cost a format bump doesn't need (see B2).
- **STATIC cron, delay-N-days durable resume, condition steps** — exactly
  the NocoBase-flavored automation parts that fight the org grain; cut
  from B5.
- **`jetpacs-lint-spec` as a pre-deploy gate** — category error: it
  validates SDUI node trees, not the CRUD spec, and needs the Emacs the
  composer deliberately avoids (constraint 5). If a headless lint is
  wanted it belongs in the elisp CI path where Emacs already runs.
- **Pixel-identical live device-runtime mirror** — [XL]. Driving the real
  runtime as a headless oracle remains rejected at current value/cost. The
  existing Composer-native semantic preview remains useful editor tooling, but
  further renderer duplication is paused in favor of improving the vocabulary
  and Elisp compiler that feed Jetpacs itself.
- **A `form` datasource *kind* and a `form_container` *node*** — a
  category error and unnecessary: A5 composes the form from existing
  auto-publishing primitives with no new kind, node, action, or bump.

## Sequencing

Tier 0, Tier A, the FORMAT-2 cutover, and Composer-owned Tier B work landed
behind the parser-parity corpus. Reminder publication, the off-app capture tile,
and native Gantt rendering remain honestly gated by their Jetpacs frontier
items. The Jetpacs-native abstraction track is now the active composer-owned
work; preview P3+ is paused and Tier C accretes as demand shows.
F2/F3/F4/F5/F6/F8 graduate to Jetpacs implementation
plans when their consumers need native framework work.

---

*Provenance: distilled from a multi-agent pass that mined NocoBase's
subsystems (field-interfaces, block/view taxonomy, relations/data-source,
actions/workflow/ACL) against the three repos — 8 subsystem briefs, 30
merged candidates, 26 adversarially vetted against the locked constraints
with file:line verification. Effort tags are honest (S/M/L/XL);
value tags reflect fit to a single-user org-on-phone builder, not generic
platform completeness.*
