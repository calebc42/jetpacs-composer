# Candidate engine packs — the committed survey (S4.6)

**STATUS: survey only — follow-on targets, deliberately NOT executed.**
Stage 4 (PLAN-pack-targeting.md) shipped the machinery: FORMAT 4 pack
references, the manifest store + pickers, the fail-closed runtime
binding, and the manifest-driven deployer, all proven against
`glasspane-pack.json` (the reference pack, generated from
`jetpacs-source-catalog` + `jetpacs-action-catalog` by
`glasspane-pack.el`). This document is the ordered answer to "which
engine gets the next pack" — each candidate assessed pack-shaped:
what its manifest's `sources`/`actions`/`depends` would say.

Inputs: the elisp-expansion package survey Part II ranks 3–4 (doctrine
criteria: archive tier, deps/battery, phone-fit) and the Glasspane
engines shortlist (`Glasspane/docs/PLAN-vulpea-ecosystem-exploration.md`
Part B). The **skins/substrate track** (hypertext, magit-section —
expansion Parts III/IV) is a separate later track: where a candidate's
UI leans on those substrates, the pack still owns only sources/actions.

## Doctrine, applied to packs

A pack candidate must be **library-first** (a usable non-interactive
API), **data-rich** (queryables → `jetpacs-defsource`, mutators →
`jetpacs-defaction`), **headless-capable**, and **dependency-sane**
(pure elisp > Termux binary [fine: shared signature] > resident daemon
[avoid]). Archive tier orders ties: built-in > GNU ELPA > NonGNU ELPA >
MELPA-only. Rendering stays the app/composer's business — a pack ships
vocabulary, never widgets.

## From the expansion survey (ranks 3–4)

| Candidate | Tier | Pack shape (sources / actions / depends) | Verdict |
|---|---|---|---|
| **gptel** | NonGNU | sources: `gptel.conversations` (chat ≈ org text); actions: `chat.send`, `chat.new`, model/backend setters (typed enum args) | **Cheapest high-wow win.** Chat buffer is org text + a composer bar — the existing record-card + text-input vocabulary covers it. Best first proof after glasspane. |
| **ledger / hledger** | MELPA + Termux binary | sources: `ledger.register`, `ledger.balance` (report tables → the native table node); actions: `ledger.capture` (typed entry template) | **Strong PKM adjacency** (budgets next to notes). The binary is a Termux install — exactly what the Deployer's binary-dep warning path (S4.4) was built for. Capture-first scope. |
| **EBDB** | GNU ELPA | sources: `ebdb.contacts` (typed records: name/mail/phone/tags); actions: `contact.create`, `contact.edit-field` | Clean record store, medium adapter for its custom formatting. App-shaped, not substrate-shaped. |
| **EMMS** | GNU ELPA | sources: `emms.playlist`, `emms.browser`; actions: `player.play/pause/next` (`media.key` exists) | Playlist adapter is easy; a lock-screen MediaSession is a **new negotiated Kotlin capability — flagged, not built** (core adds nothing un-negotiated). |
| **ement** | GNU ELPA | sources: `ement.rooms`, `ement.timeline`; actions: `room.send`, `room.join` | Pack = sources/actions ONLY: the room list rides the magit-section track, message bodies ride the hypertext track (both later). Sequence after those substrates land. |

## From the Glasspane engines shortlist

| Candidate | Tier | Pack shape | Verdict |
|---|---|---|---|
| **org-ql** | MELPA | Already a `glasspane-pack.json` depend; a standalone `org-ql` pack would expose `org-ql.query` directly | Effectively shipped — `glasspane.org` IS the org-ql source. A core-only re-cut is only worth it post-repo-split. |
| **org-srs** | MELPA | sources: `srs.due`, `srs.stats`; actions: `srs.rate` | Already shipped app-side (glasspane-srs.el review flow). Pack extraction = migration, not new capability; defer until a second SRS consumer exists. |
| **elfeed** | NonGNU | sources: `elfeed.entries` (DB + query API, already query-shaped); actions: `entry.mark-read`, `feed.update` | Best *reader* candidate, but entry bodies are shr — full value lands with the hypertext substrate. Sources-only pack is viable earlier. |
| **citar** | MELPA | sources: `citar.bibliography`; actions: `cite.insert`, `citar.open` | Clean bib DB API; niche audience. After the PKM-conversion wave, not before. |
| **denote** | GNU ELPA | sources: `denote.notes` (filename-based, lighter than vulpea); actions: `note.create` | Overlaps vulpea's territory (the decided index). Only worth a pack as a *lighter alternative* for BYO-config users without sqlite. |
| **org-transclusion** | GNU ELPA | actions: `transclude.add/remove`; sources: none obvious | Live document composition is an editor concern; weak source story. Skip for packs. |

## Skip / defer (doctrine reasons, carried over)

telega (tdlib daemon — battery/build), pdf-tools (epdfinfo C; phone
PDFs → `intent.start`), vterm (C module; comint path preferred),
notmuch/mu4e (heavier deps; mail message views wait on the hypertext
substrate anyway).

## Recommended second-pack proof (stretch, one evening)

**gptel or ledger-capture — whichever the owner prefers** — built
exactly as glasspane-pack was: `jetpacs-defsource`/`jetpacs-defaction`
registrations in the engine's adapter, a `*-pack.el` generator emitting
the byte-stable `*-pack.json` from the live catalogs, and the JSON
committed + pinned by a test. gptel proves the pack model beyond the
org/vulpea family; ledger-capture additionally exercises the Termux
binary-dep warning path end to end.
