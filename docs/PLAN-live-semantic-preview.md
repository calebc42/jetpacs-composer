# Plan: live semantic preview

**STATUS (2026-07-12): ready to implement.** This is a desktop-only preview
track over the current FORMAT-2 `AppSpec`. It is intentionally separate from
the rejected pixel-identical device-runtime mirror: the preview explains the
app's structure, content, navigation, and affordances while the user edits; it
does not claim to reproduce Jetpacs rendering pixel for pixel.

## Outcome

The editor becomes `Outline | Form | Preview`. The preview looks like a compact
phone, follows `EditorSession.spec` on every edit, and can navigate between the
app's tabs, drawer destinations, groups, and record details. It requires no
Emacs process, connected device, bundle export, or serialization round-trip.

Success means a user can answer these questions before deployment:

- Are the app label, destinations, grouping, ordering, and icons coherent?
- Does each view kind communicate the intended information hierarchy?
- Do schema labels, types, references, actions, empty states, and detail sheets
  compose sensibly?
- What is real inline/local data, what is representative sample data, and what
  behavior remains device-only?

The preview remains a convenience, never an export or deployment gate.

## Hard boundaries

### In scope

- A pure `AppSpec -> PreviewApp` projection.
- Semantic renderers for every current `ViewKind`.
- Deterministic, schema-aware representative data when the source is not
  locally readable.
- Inline table/checklist data exactly as stored in `BodyElement`.
- Preview-local navigation, search, filters, detail sheets, and action/dialog
  demonstrations.
- Optional read-only local-source loading in a later phase.
- Explicit capability/fallback notices for notes, charts, timelines,
  reminders, capture, intents, and other device/runtime behavior.

### Out of scope

- Driving Emacs or a device as a headless rendering oracle.
- Reimplementing the Jetpacs SDUI renderer or promising pixel parity.
- Executing `crud.*` actions, Android intents, reminders, capture, refile,
  archive, or any source mutation.
- Writing preview state or generated sample data into `app.org`.
- Inventing arbitrary layout, per-node styling, or a second format vocabulary.
- Treating preview success as proof that runtime parsing or deployment works.

## Architecture

The preview has three layers with one-way data flow:

```text
EditorSession.spec
       |
       v
PreviewProjection  --->  PreviewApp / PreviewView / PreviewRecord
       |                         |
       |                         v
PreviewDataResolver       SemanticPreview (Compose)
       |
       +-- inline data / deterministic samples / optional local read-only data
```

### 1. Projection

`PreviewProjection` is pure Kotlin. It projects app chrome and view semantics:

- launcher label/icon;
- tab, drawer, and grouped destinations in runtime order;
- field labels and `ColType` formatting;
- action labels and disabled preview affordances;
- reference targets by stable view slug;
- dashboard metrics and Gantt field roles;
- invalid/unknown state as renderable notices rather than exceptions.

It must accept incomplete specs produced mid-keystroke. Validation problems are
inputs to the preview, not reasons to stop projection.

### 2. Data resolution and provenance

Every preview dataset carries a visible provenance:

| Mode | Source | Behavior |
|---|---|---|
| `Inline` | `BodyElement.Table` / `Checklist` | Render exact document data. |
| `Sample` | Schema-aware deterministic generator | Default for external/device-only data; show a `Sample data` badge. |
| `Local` | Explicitly readable desktop file/directory | Later opt-in, read-only, show the resolved path and refresh state. |
| `Empty` | User-selected or genuinely empty inline source | Exercise empty states. |

Representative data is deterministic from app/view/field identity, capped at a
small row count, and type-aware:

- text and ITEM use stable human labels;
- numbers use small useful values;
- dates span overdue/today/upcoming cases;
- checkboxes mix checked and unchecked;
- enums use declared options;
- refs use IDs from generated target-view records so label resolution works;
- TODO/SCHEDULED/DEADLINE support board, calendar, reminders, and Gantt.

Inline record headings currently survive as `BodyElement.Raw`, not structured
records. P0 must add a preview-only, read-only heading/drawer projector shared
by inline records and optional local files. It must not become a competing org
writer or runtime query engine.

### 3. Compose rendering

`SemanticPreview` owns only ephemeral UI state: current route, open drawer,
selected group page, detail record, search text, simulated dialog, data mode,
and viewport. None of it belongs in `EditorSession`, undo history, or `AppSpec`.

Suggested files:

- `ui/preview/PreviewModel.kt` — normalized preview-only data classes.
- `ui/preview/PreviewProjection.kt` — pure app/navigation/view projection.
- `ui/preview/PreviewData.kt` — sample generator and read-only data resolver.
- `ui/preview/PreviewOrgRecords.kt` — narrow heading/drawer/body reader.
- `ui/preview/SemanticPreview.kt` — phone frame, app chrome, route/detail state.
- `ui/preview/PreviewViews.kt` — kind-specific composables.
- `ui/preview/PreviewSplitPane.kt` — resize handle and narrow-window behavior.

## Editor integration

The integration point is the existing `Outline | Detail` row in
`EditorScreen.kt`; extend it to `Outline | Form | Preview` and keep the preview
outside the form's vertical scroll container.

- Rename the current raw-source dialog state from `preview` to
  `sourcePreviewDialog` to avoid conflating the two features.
- Pass `session.spec` directly. Do not call `documentText()` on each edit.
- Outline/form selection drives the preview route.
- Preview tab/drawer navigation selects the corresponding form.
- Selecting the app form leaves the last view visible so app label/icon edits
  remain observable.
- Route identity prefers `ViewSpec.name`, then falls back to the previous index
  while a title is being edited or undone.
- Detail selection is preview-local and resets if its view/record disappears.

Desktop layout targets:

- outline: existing ~260 dp;
- form: minimum 480 dp;
- preview: resizable 320–520 dp, default ~380 dp;
- drag handle: 8 dp with keyboard-accessible resize alternatives;
- narrow window: switchable `Editor | Preview` mode instead of three crushed
  panes;
- consider raising the default window from 1100 dp to about 1400 dp once the
  preview opens by default.

Initially keep visibility and width in `remember(session)` state. Persisting
those preferences in `ComposerConfig` is polish, not a P0 dependency.

## View coverage

| Kind | First semantic rendering | Data/fidelity note |
|---|---|---|
| `table` | Header, typed cells, row affordances | Exact inline rows; samples/exact local rows externally. |
| `checklist` | Checked states and add affordance | Exact inline items. |
| `records` | Cards, fields, actions, detail prose | Inline heading projection or samples. |
| `notes` | Record cards and details | Sample badge unless a local vault is explicitly selected. |
| `board` | Ordered lanes and cards | Enum/TODO grouping; invalid group field gets a notice. |
| `calendar` | Month-like semantic grid/list | Dates are meaningful; no claim of native `month_grid` pixels. |
| `gallery` | Image cards and fallback placeholders | Avoid network loading in P1; local/remote image loading is later. |
| `tree` | Indented hierarchy | Read-only initially; drag simulation is later and ephemeral. |
| `dashboard` | Count/sum/avg chart cards | Use desktop Compose charts or simple bars, not Jetpacs SDUI code. |
| `gantt` | Sorted ranges first, timeline later | Match the current runtime fallback and label native timeline as device-gated. |
| `unknown` | Unsupported-kind notice with preserved metadata | Never crash or silently render as another kind. |

## Delivery phases

### P0 — projection and contracts

**Landed:** normalized preview models, pure app/navigation projection,
deterministic typed samples with reference integrity, narrow read-only org
record/tree projection, inline/sample/empty data resolution, and kind-coverage
tests are implemented. The existing raw-source dialog was disambiguated and
history selection now has slug/index reconciliation groundwork for P1.

- Add preview-only models, provenance, deterministic samples, and the narrow org
  record reader.
- Project runtime destination order/grouping and reconcile routes by slug/index.
- Add explicit invalid/unknown/empty projections.
- Rename the existing raw-source preview state.

Acceptance:

- Pure tests cover every current `ViewKind`, every `ColType`, grouping/order,
  refs, malformed partial specs, and deterministic samples.
- Canonical Hello World projects every kind without throwing.
- No preview code performs writes, invokes ADB, exports, or enters undo history.

### P1 — live split pane and phone shell

**Landed:** the editor now hosts a live phone-like preview by default with a
toolbar visibility toggle, bounded mouse/keyboard-accessible resizing, compact
Editor/Preview switching, app chrome, tabs, drawer destinations, grouped member
navigation, two-way form selection, provenance/notices, and Auto/Sample/Empty
data modes. View bodies intentionally remain summary cards until P2 renderers.

- Add Preview toggle, resizable pane, compact phone frame, top bar, bottom tabs,
  drawer, groups, and selection synchronization.
- Render provenance and validation/capability badges.
- Implement populated/empty mode switch.

Acceptance:

- Editing app/view labels, order, icons, nav, or groups updates immediately.
- Preview navigation selects the corresponding form and survives undo/title
  changes where the view still exists.
- At narrow widths the editor remains usable through Editor/Preview switching.

### P2 — core data views and details

- Implement table, checklist, records, notes, typed values, refs, actions,
  empty states, and full-record details.
- Action controls are visibly inert or open a `Preview only` explanation.
- Reference taps open generated/resolved target details without mutating data.

Acceptance:

- Inline tables/checklists are exact.
- Sample-backed records are deterministic and visibly labeled.
- Detail sheets show every schema field, body prose when available, references,
  and declared actions.

### P3 — specialized views

- Add board, calendar, gallery, tree, dashboard, and Gantt.
- Reuse the same `PreviewRecord` dataset rather than building per-kind parsers.
- Label differences between semantic desktop rendering and native/fallback
  device capabilities.

Acceptance:

- Hello World has a non-crashing, meaningful preview for every kind.
- Invalid field configuration produces an inline notice plus the closest safe
  fallback.

### P4 — safe interaction simulation

- Add preview-local search, supported filter evaluation, group switching,
  details, menus, dialogs, checkbox/TODO demonstrations, and tree reordering.
- Add a prominent reset button whenever simulated state diverges from the spec.

Acceptance:

- Simulated edits never change `EditorSession.spec`, `dirty`, undo/redo, org
  files, or external sources.
- Unsupported actions explain the runtime/device dependency.

### P5 — optional read-only local data and profiles

- Resolve relative sources from `session.file.parentFile`; allow explicit local
  absolute paths only when readable.
- Add manual refresh and clear error/provenance states; no watcher in the first
  iteration.
- Add viewport presets, light/dark preview theme, and capability profiles (core,
  chart-capable, future timeline) to exercise fallbacks.

Acceptance:

- Local loading is opt-in/read-only and never follows a device `/sdcard` path
  into invented desktop data.
- Switching profile changes only preview capability/fallback presentation.

### P6 — hardening

- Add semantic UI tests once the project has a Compose UI-test dependency.
- Add a small number of stable screenshot goldens for shell/core/specialized
  states; avoid a pixel-golden matrix for every field permutation.
- Profile projection/recomposition with Hello World and large schemas.
- Add accessibility descriptions, keyboard navigation, focus order, and
  minimum contrast/touch target checks.

Acceptance:

- A normal keystroke does bounded pure projection over a capped dataset and
  does no file I/O.
- Preview failures are contained to an error card; the form remains editable.
- Kind coverage fails tests when a new `ViewKind` lacks a projector/renderer.

## Test strategy

Start with pure tests because the project does not currently include Compose UI
test infrastructure:

- `PreviewProjectionTest` — chrome, groups, order, route reconciliation,
  invalid/unknown states.
- `PreviewDataTest` — deterministic samples, type formatting, ref integrity,
  inline/local/empty provenance.
- `PreviewOrgRecordsTest` — headings, drawers, planning lines, body prose,
  subtree boundaries, malformed input.
- `PreviewAggregatesTest` — board lanes, calendar dates, dashboard aggregates,
  Gantt ordering/ranges.
- `PreviewKindCoverageTest` — every non-unknown enum value has a renderer and is
  present in canonical Hello World.

Later UI tests cover split-pane behavior, editor/preview selection sync,
drawer/tabs/details, narrow mode, and the guarantee that simulated actions do
not dirty the session.

## Anti-drift rules

- `AppSpec` and FORMAT.md remain authoritative; preview-only models are derived.
- Runtime parser parity tests remain the deployability contract; preview tests
  do not replace them.
- New `ViewKind` values must add projection, renderer, fallback, sample data,
  and Hello World coverage in the same change.
- If semantic behavior cannot be derived without executing Emacs/org runtime
  code, show an explicit device-only notice instead of guessing silently.
- Pixel-identical device mirroring remains a separate future project and should
  reuse the real renderer/runtime rather than expanding this preview into one.
