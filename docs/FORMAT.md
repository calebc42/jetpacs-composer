# The Jetpacs CRUD app format, v1 — `app.org`

**STATUS: frozen for v1.** This document is the contract between the
composer (the desktop editor), `jetpacs-crud-orgapp.el` (the on-device
parser), and `jetpacs-crud.el` (the runtime). Anything not listed here
is not part of the format; adding a keyword, drawer property, column
type, or action requires bumping `#+JETPACS_APP_FORMAT`.

An app is **one org file**. Everything the runtime needs is in it; the
data it manages lives in org tables — either inline in this file or in
external org files it references (the "backend").

## File-level keywords

Case-insensitive, like all org keywords.

| Keyword | Required | Meaning |
|---|---|---|
| `#+JETPACS_APP: <id>` | yes | App id — a slug matching `[a-z][a-z0-9-]*`. Its presence is what makes the file an app. |
| `#+TITLE:` | no | Launcher label (default: capitalized id). |
| `#+JETPACS_ICON:` | no | Material icon name for the launcher card (default `apps`). |
| `#+JETPACS_ORDER:` | no | Integer sort key for the launcher home (default 100). |
| `#+JETPACS_APP_FORMAT:` | no | Format version; default and only valid value: `1`. |

## Views

**Every top-level (level-1) heading is a view** — one bottom-bar tab
each. The heading title is the tab label. Configuration lives in the
heading's property drawer:

| Property | Meaning |
|---|---|
| `:ICON:` | Tab icon (Material name; default `table_chart` for tables, `checklist` for checklists). |
| `:ORDER:` | Tab order (integer; default: 10, 20, … in document order). |
| `:KIND:` | `table` (default), `checklist`, or `records`. |
| `:SOURCE:` | Where the data lives — see below. Default `inline`. |
| `:COLTYPES:` | Table and records views: per-column/field types, space-separated, positional. |
| `:COLUMNS:` | Table views with an external `:SOURCE:`: column names, `|`-separated, used to scaffold the backend table when its file doesn't exist yet. |
| `:SCHEMA:` | Records views (required): the fields, as org column-view-style tokens — `%PROP` or `%PROP(Label)`. |
| `:FILTER:` | Records views: an org match string (the `org-map-entries` / agenda-tags syntax, e.g. `+active+Tier="Gold"`), selecting which records show. |

### `:SOURCE:`

- `inline` (default) — the data is the first org table (or, for
  checklists, the first checkbox list) in this view's own subtree.
  The app file is then also a data file.
- `/absolute/path/file.org` — the first table/list in that file.
- `/absolute/path/file.org::*Heading title` — the first table/list
  under that heading.

If an external source file does not exist at registration time, the
runtime creates it when it can: table views need `:COLUMNS:` (for the
header row); checklist views need no schema, so their file (and heading,
if given) is always scaffolded. A table view without `:COLUMNS:` whose
source is missing renders an empty-state, never an error.

### `:COLTYPES:` — the column types

Positional, one token per table column:

| Token | Meaning | Edit affordance | Rendering |
|---|---|---|---|
| `text` | free text (the default for unlisted columns) | native text dialog | as-is |
| `number` | numeric | text dialog, numeric-validated | right-aligned |
| `date` | `YYYY-MM-DD` | text dialog, format-validated | as-is |
| `enum(A,B,C)` | one of the listed options | native single-choice dialog | as-is |
| `checkbox` | `[X]` / `[ ]` | tap toggles directly | checkbox icon |

## Table views (`:KIND: table`)

The first row of the table is the header (= the schema's column names);
a rule after it is conventional but not required. Rendering and
interaction:

- Tap a cell → edit it (typed by `:COLTYPES:`).
- Tap a checkbox-column cell → toggle it.
- Long-press a cell → row menu: *Insert row above · Delete row · Edit cell*.
- The `+` strip under the table, and the view's FAB → append a row
  (a typed prompt per column, in order).
- A `#+TBLFM:` line after the table is respected: mutations trigger
  recalculation Emacs-side. (Editing formulas from the phone is out of
  scope for v1 — a formula-computed cell is simply overwritten on next
  recalc if hand-edited.)

## Checklist views (`:KIND: checklist`)

The source is a checkbox plain list. Tap the checkbox icon → toggle.
The FAB appends a new unchecked item (one text prompt).

## Records views (`:KIND: records`) — org's native record shape

A record is a heading with a property drawer: the shape existing org
files already have. The records of a view are the **direct children**
of the `:SOURCE:` heading (or the level-1 headings of the file when no
heading is given); anything outside those subtrees is never touched.

`:SCHEMA:` declares the fields as org column-view-style tokens,
optionally typed positionally by `:COLTYPES:`:

```org
* People
:PROPERTIES:
:KIND: records
:SOURCE: /sdcard/org/contacts.org::*Contacts
:SCHEMA: %ITEM(Name) %TODO(Status) %Phone %Tier %DEADLINE(Renewal)
:COLTYPES: text text text text date
:FILTER: Tier="Gold"
:END:
```

Core org does the heavy lifting — this is deliberate:

- **Special properties are first-class fields.** `ITEM` (the heading
  text), `TODO`, `DEADLINE`, `SCHEDULED`, `PRIORITY` read and write
  through org's own machinery (`org-entry-get`/`org-entry-put`), so a
  `TODO` field cycles the file's *real* keyword sequence (`#+TODO:`
  lines respected), and `DEADLINE` writes a real planning line.
- **Enums come from the file.** org's allowed-values convention — a
  `Tier_ALL` property (drawer or `#+PROPERTY:` line) — supplies the
  choice list for `Tier`; when present it wins over `:COLTYPES:`.
  `TODO` gets its choices from the keyword sequence the same way.
- **Filtering is org's own match syntax** (`:FILTER:`), not a new
  language.

Rendering: one card per record — title line (`ITEM`, prefixed by the
`TODO` keyword when in the schema), then one tappable row per remaining
field. Tap a field → typed edit; the card menu deletes the record
(with confirmation); the FAB adds one (typed prompt per schema field,
appended at the end of the source subtree).

A missing external source is scaffolded (file + heading); records
themselves come from the FAB or from your own editing.

## What the runtime does to your files (read before pointing at one)

Bringing your own org file is the point of records views — and the
cost of the abstraction is that **the runtime must own the layout of
the records it manages**. Mutations go through org-mode's own
commands, which normalize what they touch:

- Editing a field creates or reindents the record's property drawer
  and rewrites the affected property/planning line.
- Deleting a record deletes its **entire subtree** — body text
  included. The confirmation dialog is the only guard.
- Adding a record appends a normalized heading at the end of the
  source subtree.

Text *outside* managed records (prose, other headings, the source
heading's own body) is never touched, and record body text survives
field edits — but hand-crafted formatting *inside* a managed drawer or
planning line will not. **Keep BYO files under version control or
backups.** This is the same deal desktop org-mode users already accept
from `org-entry-put`, made explicit.

## Actions (the closed vocabulary)

The wire names these and only these; all are implemented in
`jetpacs-crud.el` against org-mode primitives, and every handler
validates that the `file` argument is one of the registered app's
declared sources before touching anything.

`crud.cell.edit` · `crud.cell.toggle` · `crud.row.add` ·
`crud.row.menu` · `crud.checkbox.toggle` · `crud.item.add` ·
`crud.field.edit` · `crud.record.add` · `crud.record.menu`

Every mutation ends in: save file → repush all views (positions are
recomputed from a fresh parse on every render, so they can never go
stale).

## Explicit v1 non-goals

No conditionals, no formula editing, no arbitrary layout, no per-node
styling, no cross-source references, no tag editing, no filters on
table views. These are deliberate; see the plan's Deferred section
before proposing one.

> Format note: records views (`:KIND: records`, `:SCHEMA:`, `:FILTER:`)
> were folded into v1 pre-release, before any installed base existed.
> `#+JETPACS_APP_FORMAT:` stays `1`.

## Example (the canonical fixture)

See [`elisp/test/fixtures/pantry.org`](../elisp/test/fixtures/pantry.org) —
an Inventory table view (inline source, five typed columns) plus a
Shopping checklist view, in ~25 lines.
