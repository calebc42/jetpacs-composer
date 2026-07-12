# jetpacs-composer

**A desktop builder for no-code CRUD apps on the [Jetpacs](https://github.com/calebc42/jetpacs) framework.**

The AppSheet model, org-native: where AppSheet builds apps around a
spreadsheet, jetpacs-composer builds them around **org tables**. You
describe an app declaratively — views, datasources, column types — and
it runs on your phone as a native-UI Jetpacs app, with org files as the
backend and Emacs as the brain. No code in the app document, ever.

```
  composer (desktop, Compose/Kotlin)          the device
  ┌──────────────────────────────┐    ┌──────────────────────────┐
  │ visual editor over app.org   │    │ Emacs (Termux) loads the │
  │  · views, schema, types      │ →  │ bundle; jetpacs-crud     │
  │  · exports one .el bundle    │    │ registers the app in the │
  │  · deploys via adb / sshd    │    │ Jetpacs launcher; org    │
  └──────────────────────────────┘    │ files hold the data      │
                                      └──────────────────────────┘
```

## The pieces

- **[docs/FORMAT.md](docs/FORMAT.md)** — the current v2 `app.org` format:
  one org file describes the whole app. Hand-authorable; the composer is
  a convenience, not a gatekeeper.
- **[elisp/jetpacs-crud.el](elisp/jetpacs-crud.el)** — the runtime: a
  generic CRUD engine over org tables (tap-to-edit cells typed by
  `:COLTYPES:`, add/delete rows, checklists), depending only on
  `jetpacs-core`. The closed `crud.*` action vocabulary is the entire
  wire surface.
- **[elisp/jetpacs-crud-orgapp.el](elisp/jetpacs-crud-orgapp.el)** — the
  `app.org` parser and install surface (`jetpacs-crud-install`,
  `jetpacs-crud-load-directory`).
- **[elisp/build-app-bundle.el](elisp/build-app-bundle.el)** — one
  `app.org` in, one self-contained `jetpacs-app-<id>.el` out (runtime +
  document + install call). Load it after `jetpacs-core` and the app
  appears in the launcher.
- **The desktop editor** (Compose Desktop, in progress) — visual forms
  over the format, export, and one-click deploy to a device.

## Try it today (no composer needed)

```sh
# Build the bundle from the example app:
emacs -Q --batch -l elisp/build-app-bundle.el -- elisp/test/fixtures/pantry.org .

# Ship it with the jetpacs deploy script (stages to /sdcard/Download):
../jetpacs/deploy.ps1 -Bundles jetpacs-app-pantry.el
```

On the device, add `"jetpacs-app-pantry.el"` to the starter init's
bundle list with a `(require 'jetpacs-app-pantry)` (see
`jetpacs/docs/starter-init.el`), restart Emacs — a Pantry app with an
editable inventory table and a shopping checklist appears in the
Jetpacs launcher.

## Tests

```sh
# From Windows:
wsl -d Debian -- sh elisp/test/run-tests.sh
```

ERT covers parsing, registration, lint-clean rendering against the
wire goldens, every mutation path, and the validation boundary; the
bundle smoke test loads a built bundle against the bare jetpacs core —
the exact on-device situation.

## License

GPL-3.0-or-later, like the rest of the Jetpacs ecosystem. The runtime
adapts table/checkbox rendering patterns from
[Glasspane](https://github.com/calebc42/glasspane) (same author).
