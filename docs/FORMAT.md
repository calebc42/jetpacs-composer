# The Jetpacs CRUD app format, v2 — `app.org`

**STATUS: current format.** This document is the contract between the
composer (the desktop editor), `jetpacs-crud-orgapp.el` (the on-device
parser), and `jetpacs-crud.el` (the runtime). Anything not listed here
is not part of the format; adding a keyword, drawer property, column
type, or action requires a deliberate format change. There was no installed
v1 app base, so v2 is a clean cutover: explicit v1 documents are rejected and
there is no migration ladder. A missing version means the current version.

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
| `#+JETPACS_APP_FORMAT:` | no | Format version; default and only valid value: `2`. The canonical writer always emits it. |
| `#+JETPACS_INBOX:` | no | App-scoped quick-capture destination; relative paths resolve beside the app document. |
| `#+TODO:` | no | Org TODO keyword sequence used by TODO fields and actions. |
| `#+TAGS:` | no | File tag vocabulary offered by the composer. |

## Views

**Every top-level (level-1) heading is a view.** By default each is its
own bottom-bar tab, with the heading title as the label; `:NAV:` and
`:GROUP:` (see *Navigation placement*) move it elsewhere. Configuration
lives in the heading's property drawer:

| Property | Meaning |
|---|---|
| `:ICON:` | Tab icon (Material name; default `table_chart` for tables, `checklist` for checklists). |
| `:ORDER:` | Tab order (integer; default: 10, 20, … in document order). |
| `:KIND:` | `table` (default), `checklist`, `records`, `notes`, `board`, `calendar`, `gallery`, `tree`, or `dashboard`. |
| `:SOURCE:` | Where the data lives — see below. Default `inline`. |
| `:COLTYPES:` | Table and records views: per-column/field types, space-separated, positional. |
| `:COLUMNS:` | Table views with an external `:SOURCE:`: column names, `|`-separated, used to scaffold the backend table when its file doesn't exist yet. |
| `:SCHEMA:` | Records and notes views (required): the fields, as org column-view-style tokens — `%PROP` or `%PROP(Label)`. |
| `:FILTER:` | Records and notes views: a query selecting which records show — an org-ql sexp, filter tokens, or free text (see below). |
| `:GROUP_BY:` | Board views: schema field used for lanes (default `TODO`). |
| `:DATE_FIELD:` | Calendar views: schema field containing the org timestamp/date (default `DEADLINE`). |
| `:IMAGE_FIELD:` | Gallery views: schema field containing the image URL/path (default `IMAGE`). |
| `:METRICS:` | Dashboard views: `|`-separated `count`, `sum(FIELD)`, or `avg(FIELD)` chart blocks. |
| `:ACTIONS:` | Records-like views: closed, space-separated org action tokens (see below). |
| `:ON:` | Optional closed automation type; currently only `date-field`. |
| `:REL:` | With `:ON: date-field`, a whole-day offset such as `-3d`, `0d`, or `+1d`. |
| `:DATEFIELD:` | With `:ON: date-field`, the schema property supplying the org date/timestamp. |
| `:NAV:` | `tab` (default) or `drawer` — where the view lives in the chrome (see below). |
| `:GROUP:` | A destination name; views sharing one collapse into a single tabbed bottom destination (see below). |

### Navigation placement — `:NAV:` and `:GROUP:`

A Material bottom bar holds only about five destinations comfortably, so
a many-view app spreads across the chrome:

- **`:NAV: drawer`** routes a view into the navigation drawer (the ☰
  hamburger) instead of the bottom bar. The view is still shipped and
  switching to it is instant (no round-trip) — it just isn't a tab. Use
  it for secondary or reference views. `:NAV: tab` (the default) keeps
  the view on the bottom bar.
- **`:GROUP: Name`** folds a view, together with every other view that
  names the same group, into **one** bottom destination whose body is a
  top tab row (swipe or tap between the members). The destination's
  label is the group `Name`; its icon and bar position come from the
  first member (lowest `:ORDER:`). This is the "one dataset seen several
  ways" pattern — e.g. a task list, board, and calendar under `Tasks`.

`:GROUP:` wins over `:NAV:` (a grouped view is part of its destination,
not a standalone drawer entry). The group shares a single add button —
the first member's — so tapping **+** from any of its tabs adds through
that member's view. `:GROUP:` is a placement name and is unrelated to
records' `:GROUP_BY:`, which lanes a single board's records.

### `:SOURCE:`

- `inline` (default) — the data is the first org table (or, for
  checklists, the first checkbox list) in this view's own subtree.
  The app file is then also a data file.
- `/absolute/path/file.org` — the first table/list in that file.
- `/absolute/path/file.org::*Heading title` — the first table/list
  under that heading.
- `/absolute/path/vault/` (trailing slash) — **notes** views only: a
  vulpea vault directory, where every `.org` note file is one record
  (see Notes views).

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

Unknown future column-type tokens load as opaque text fields and are preserved
verbatim. `ref(View)` stores the target record's stable org `:ID:`; the optional
`ref(View,FIELD)` form renders `FIELD` instead of the target's `ITEM`. `View`
must name a records or notes view in the same app, and records targets must
include `ID` in their schema. Editing presents the target records as choices,
while tapping drills in by `(app, view, id)`, never by sending a source path
over the wire.

Table views additionally offer paste-driven CSV import. The first row must
exactly match the current table header. Every subsequent row must have the same
width and pass its declared column type (`number`, `date`, `checkbox`, or
`enum`) before any source mutation begins; the first error reports its CSV row,
column, and label. A valid batch appends atomically through the normal org-table
mutation path. CSV import does not synthesize records or notes.

### Date-field reminders

A record-like view may derive durable reminders from org dates:

```org
:ON: date-field
:REL: -3d
:DATEFIELD: DEADLINE
```

The rule reads the declared schema field through core org, adds the whole-day
offset, and identifies each reminder by app, view, stable record ID, field, and
offset. Records therefore need an `ID` schema field; notes use their native ID.
Transient search text does not suppress alarms. Reminder publication requires
Jetpacs' owner-merged reminder seam: Composer will warn and arm nothing on an
older framework rather than overwrite another app's device-global reminder set.

### Quick capture

`#+JETPACS_INBOX: inbox.org` enables an app-scoped quick-capture action. The
runtime resolves this path from the registered document (relative paths are
relative to that document), prompts only for a title, and appends one top-level
heading with native `ID` and `CREATED` properties. The wire carries only the app
ID: it cannot choose a path, edit an existing entry, or delete one. Composer
exposes capture in the app top bar and registers the per-app default FAB for
app-owned views that do not already use a FAB.

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
  scope for v2 — a formula-computed cell is simply overwritten on next
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
:FILTER: (property "Tier" "Gold")
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
- **Filtering speaks org-ql's query language** (`:FILTER:`) — see below.

### `:FILTER:` — selecting records

The filter is parsed into an [org-ql](https://github.com/alphapapa/org-ql)
sexp. Three ways to write one, from most to least explicit:

| Shape | Example | Meaning |
|---|---|---|
| org-ql sexp | `(and (todo "NEXT") (tags "work"))` | full boolean query |
| filter tokens | `todo:NEXT tags:work,home priority:A` | tokens AND together; commas are any-of |
| free text | `renewal gold` | each word is a substring match on heading + body |

An empty `:FILTER:` shows every record; a malformed query is an error,
so an empty result always means "nothing matched", never "didn't parse".

The runtime carries a **built-in interpreter** for the common terms —
`and` / `or` / `not`, `todo` / `done`, `tags`, `priority`, `heading`,
`regexp`, `property`, `level`, `scheduled` / `deadline`. These work on
every device with no extra packages. When the `org-ql` package **is**
installed, a records filter that reaches beyond this subset is handed to
org-ql wholesale, so its full query language becomes available; without
org-ql such a term is a clear error naming the package, not a silent
empty view. (Filtering applies to records and notes views; table views
are unfiltered — see non-goals. Notes filter against the vulpea index,
which carries its own subset — see below.)

Rendering: one card per record — title line (`ITEM`, prefixed by the
`TODO` keyword when in the schema), then one tappable row per remaining
field. Tap a field → typed edit; the card menu deletes the record
(with confirmation); the FAB adds one (typed prompt per schema field,
appended at the end of the source subtree).

A missing external source is scaffolded (file + heading); records
themselves come from the FAB or from your own editing.

### Record actions — `:ACTIONS:`

Actions are a closed, space-separated vocabulary attached to each record card:

- `todo(KEYWORD)` · `schedule` · `deadline`
- `tags` or `tags(a,b)` · `priority` or `priority(A)`
- `refile` or `refile(TARGET)` · `archive` or `archive(STYLE)`

They dispatch through the single closed `crud.action.apply` handler and map to
org's own mutation commands. Unknown future tokens are preserved and ignored by
an older runtime rather than preventing the whole app from loading.

Table and record-like views also expose explicit top-bar export actions: copy
CSV, copy org-table text, and share CSV. CSV cells beginning with optional
whitespace followed by `=`, `+`, `-`, or `@` are prefixed with an apostrophe to
prevent spreadsheet formula execution. Share recomputes the data from the
registered view and never accepts a source or destination path from the wire.

## Derived record views

`board`, `calendar`, `gallery`, and `tree` use the same `:SCHEMA:`, `:SOURCE:`,
`:FILTER:`, card actions, and mutation boundary as records:

- **board** groups cards into ordered lanes using `:GROUP_BY:` and the field's
  allowed values.
- **calendar** marks dates from `:DATE_FIELD:` in the native month grid, with a
  grouped-card fallback when that node is unavailable.
- **gallery** reads an image URL/path from `:IMAGE_FIELD:` and otherwise renders
  the normal record card.
- **tree** walks the source outline at every depth and supports org-native
  reorder/reparent operations.

Tapping a record card—or a resolved reference value—opens a full-height detail
sheet. It uses the appropriate records/notes identity resolver, shows every
schema field with its typed/reference behavior, retains configured actions, and
adds the org entry's body prose below the fields. Writes still use the original
view-bound mutation handlers; the detail overlay does not accept a source path.

## Dashboard views (`:KIND: dashboard`)

Dashboards reuse the records source, schema, and filter contract. `:METRICS:`
declares one or more closed aggregations, for example `count | sum(Amount) |
avg(Amount)`. Optional `:GROUP_BY:` supplies the chart's labeled x-axis; without
it every metric has one `All` point. Each metric renders as a bar-chart card.
Blank/non-numeric cells do not contribute to sum or average. When the companion
does not advertise the additive `chart` node, the dashboard degrades to the
normal records list over the same filtered data.

## Notes views (`:KIND: notes`) — a vulpea vault as the datasource

A notes view is a records view whose datasource is a
[vulpea](https://github.com/d12frosted/vulpea) note database. It is the
one datasource that needs a package on the device: **vulpea (v2+)**.
Without it the view renders a "Notes need vulpea" placeholder and the
rest of the app runs normally — the bundle never depends on vulpea, it
uses it when present.

`:SCHEMA:` works exactly as for records. `:FILTER:` is matched against
the vulpea **index** (no file scan), so it covers `and` / `or` / `not`,
`todo`, `tags`, `property`, `regexp`, `level` — other terms (and the
org-ql extension available to records) do not apply here; narrow the
`:SOURCE:` instead. Fields are org **properties** on each note, which
vulpea indexes. The `:SOURCE:` picks one of two record shapes:

- `contacts/` (a trailing-slash directory) — **file-per-record**: every
  `.org` note file in the vault is one record. Adding a record writes a
  new note file (`<slug>.org`, with an `:ID:`); deleting one deletes the
  file.
- `people.org::*Team` — **heading-per-record**: the id'd headings
  directly under that heading are the records, same as a records view
  but addressed by their stable note `:ID:` rather than file position.

```org
* People
:PROPERTIES:
:KIND: notes
:SOURCE: /sdcard/org/contacts/
:SCHEMA: %ITEM(Name) %Phone %Tier
:COLTYPES: text text enum(Gold,Silver,Bronze)
:FILTER: (property "Tier" "Gold")
:END:
```

Why vulpea rather than a raw file scan: its SQLite index supplies the
list without opening every file, and a record keeps a stable `:ID:`
across external edits (Syncthing, git) — so a tapped card still resolves
to the right note after the vault has been re-sorted underneath it. The
runtime only ever *reads* from vulpea and asks it to re-index a file it
just wrote; the writes themselves go through org and `org-id`. On the
device, install vulpea and enable `vulpea-db-autosync-mode` (the starter
init does this) so the index tracks the vault.

The wire adds `crud.note.add`, `crud.note.field.edit`, and
`crud.note.menu` for these views; the record's `:ID:` travels on the
wire, but a handler still refuses any note whose file falls outside the
view's declared `:SOURCE:`.

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

### Redeploying an app (update in place)

An app's document lives on the device under the apps directory; the
first install writes it verbatim. A **later install of the same id
updates in place** instead of clobbering — it adopts the redeployed
document's *structure* while keeping the device's *data*:

- Adopted from the new document: the file keywords, the set of views and
  their order, view prose, and every view's **property drawer** (`:KIND:`,
  `:ICON:`, `:SCHEMA:`, `:NAV:`, `:GROUP:`, …). So a layout or config
  change reaches the device on the next deploy.
- Kept from the device: each still-present view's **body** — the inline
  table rows, checklist items, and records the user has edited. Views are
  matched **by heading title**; a view the new document drops is removed
  (its data with it), a new view arrives with its template body, and
  *renaming* a view in the composer resets that view's data (it reads as
  drop-plus-add).
- If the merged result cannot be parsed back — a corrupt on-device file —
  the existing document is kept untouched rather than risk its data, and a
  warning is logged.

So iterating on structure is safe: redeploy and the new drawers/views
appear without wiping inline data. The one thing not carried across a
redeploy is on-device edits to a view's *prose* — that follows the body,
so composer prose changes don't overwrite it once the view has data.

## Actions (the closed vocabulary)

The wire names these and only these; all are implemented in
`jetpacs-crud.el` against org-mode primitives, and every handler
validates that the `file` argument is one of the registered app's
declared sources before touching anything.

`crud.cell.edit` · `crud.cell.toggle` · `crud.row.add` ·
`crud.row.menu` · `crud.checkbox.toggle` · `crud.item.add` ·
`crud.field.edit` · `crud.field.state-sink` · `crud.record.add` ·
`crud.record.add.submit` · `crud.record.detail` · `crud.record.menu` ·
`crud.record.duplicate` · `crud.note.add` · `crud.note.field.edit` ·
`crud.note.menu` · `crud.view.search` · `crud.action.apply` ·
`crud.node.move` · `crud.dialog.dismiss`

Every mutation ends in: save file → repush all views (positions are
recomputed from a fresh parse on every render, so they can never go
stale).

## Explicit v2 non-goals

No conditionals, no formula editing, no arbitrary layout, no per-node
styling, no cross-source references, no tag editing, no filters on
table views. These are deliberate; see the plan's Deferred section
before proposing one.

> Format note: v2 is a clean pre-release cutover. There is no installed v1
> base and therefore no v1 migration or fallback path. All canonical fixtures,
> templates, generated documents, and runtime parsers move together.

## Examples (the canonical fixtures)

Two documents pin the format, smallest first:

- [`elisp/test/fixtures/pantry.org`](../elisp/test/fixtures/pantry.org) —
  the minimal app: an Inventory table view (inline source, five typed
  columns) plus a Shopping checklist view, in ~25 lines.
- [`elisp/test/fixtures/hello-world.org`](../elisp/test/fixtures/hello-world.org) —
  the kitchen sink: every view kind, every column type, all eight
  schema specials, a filter, the full action vocabulary, a scaffolded
  external source, and a notes vault.  Three consumers keep it honest
  (ERT parse+register+lint, the bare-core bundle smoke, and OrgCodec
  parse/validate/round-trip as the gallery's demo template), and
  kind-coverage tests on both sides fail whenever a new view kind
  lands without growing it.  The prebuilt bundle ships at the repo
  root as `jetpacs-app-hello-world.el` — push it to a device and load
  it to exercise everything at once.
