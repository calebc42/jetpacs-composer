# Plan: orgmode-kmp fork and Composer integration

## Decision

Maintain the fork as a **separate org-mode parsing library repository** and
consume a pinned release from Composer. Do not copy its implementation directly
into `jetpacs-composer`.

The boundary is semantic:

- `orgmode-kmp` owns lossless, general-purpose org lexing, parsing, source
  locations, tree edits, diagnostics, and reconstruction.
- Composer owns FORMAT, `AppSpec`, validation, scaffolding, and the adapter from
  a general org tree to the Jetpacs application vocabulary.
- The bundled Elisp remains the runtime oracle for how a generated app behaves
  once Emacs loads it into Jetpacs.

This preserves reuse by Composer, Jetpacs/Android tools, Glasspane tooling, and
future non-Composer consumers. It also keeps the upstream Apache-2.0 lineage and
notices clear while Composer remains GPL-3.0-or-later.

## Why not vendor it into Composer?

The project is already a Kotlin Multiplatform library with JVM, Android, and
iOS variants plus Maven publishing. Composer is currently a JVM desktop app.
Embedding the parser would couple a reusable grammar to FORMAT-specific release
cycles, obscure upstream history, and make other consumers depend on the
Composer repository.

The current parser is promising but not yet a drop-in `OrgCodec` replacement:

- heading TODO parsing recognizes only `TODO` and `DONE`, while Composer files
  support custom `#+TODO` sequences;
- `parse` returns `null` rather than structured diagnostics;
- token retention proves unchanged round trips, but structural mutation needs
  an explicit ancestor/token-rebuild contract;
- several org constructs remain intentionally incomplete.

## Repository contract

Keep Jetpacs concepts out of the parser fork. In particular, it must not know
about `JETPACS_APP`, view kinds, coltypes, actions, `AppSpec`, or deployment.
Those are interpreted by a Composer adapter.

Fork priorities:

1. Configurable/custom TODO and done keyword sequences.
2. Structured parse diagnostics with offsets/ranges and recovery nodes.
3. Case-insensitive org keywords, drawers, planning lines, and special fields.
4. Safe tree-edit APIs that rebuild affected token streams and preserve all
   untouched text byte-for-byte.
5. Coverage for tables and adjacent `#+TBLFM`, nested headings, drawers,
   checklists, raw blocks, links, and unknown forward-compatible constructs.
6. A public compatibility policy and independently versioned Maven artifacts.

## Composer migration

### O1 — dependency seam

- Publish the fork under a fork-owned Maven coordinate and pin an exact version.
- Support an opt-in Gradle composite-build path for local development; do not
  make a sibling checkout or `mavenLocal()` part of reproducible CI.
- Add an internal `OrgDocumentCodec` boundary so `OrgCodec` and the KMP adapter
  can coexist.

### O2 — shadow parsing

- Parse the existing fixture corpus with both implementations.
- Compare `AppSpec`, diagnostics, and exact unchanged reconstruction.
- Keep legacy `OrgCodec` authoritative; discrepancies are test failures and
  migration input, never silently normalized files.

### O3 — read-path cutover

- Make the adapter authoritative only after parser-parity, malformed-input,
  custom-TODO, and preservation corpora agree.
- Retain the legacy codec behind a temporary diagnostic switch for one cycle.

### O4 — mutation/write cutover

- Move structured edits only after subtree/table/drawer mutations demonstrate
  byte-for-byte preservation outside the edited range.
- Remove duplicate parsing logic after the KMP path and Elisp parser-parity
  corpus remain green together.

## Acceptance

- A general org document never imports Composer or Jetpacs types.
- Canonical and malformed FORMAT fixtures produce equivalent `AppSpec` results
  and actionable diagnostics.
- Custom TODO sequences, mixed-case properties, inline data, `#+TBLFM`, and raw
  unknown constructs survive round trips.
- An edit changes only its intended source range unless org syntax requires a
  documented structural rewrite.
- Composer CI resolves a pinned artifact without depending on a neighboring
  checkout or mutable local Maven cache.
