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

## Making the checkout a real fork

Create an empty repository under the chosen account/organization, then preserve
the existing SourceHut repository as `upstream` and make the new repository the
writable `origin`:

```shell
git remote rename origin upstream
git remote add origin <your-fork-git-url>
git push -u origin --all
git push origin --tags
```

Keep `master` or deliberately rename it before the first push. The moved local
checkout currently has only an unintended-looking mode change on `gradlew`
(`100755 → 100644`); resolve that separately before the first fork commit so it
does not obscure the functional changes.

For the first fork release:

1. Change the Maven group from `xyz.lepisma` to a fork-owned namespace and
   update the POM SCM/project URLs while retaining upstream attribution and the
   Apache-2.0 license.
2. Configure the `jvm()` target explicitly for `JvmTarget.JVM_17` (or lower)
   and use a Java 17 toolchain. The Android target may remain JVM 8.
3. Run `:orgmode:jvmTest` and `:orgmode:publishToMavenLocal`, then verify a JVM
   class reports major version 61 or lower.
4. Publish a uniquely versioned artifact; update Composer's coordinate and
   promote it from `compileOnly` to `implementation`.

Routine upstream sync remains explicit:

```shell
git fetch upstream
git switch master
git merge upstream/master
git push origin master
```

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

- **Started:** Composer now has an `OrgAppCodec` boundary, a contained
  `OrgModeKmpShadowParser`, pinned dependency metadata, and an optional
  `orgmodeKmpPath` composite substitution. The maintained fork is configured as
  `com.calebc42.orgmode:orgmode:0.4.1-jetpacs.1` and its JVM target is Java 17.
  Composer still references the upstream coordinate as `compileOnly` until the
  first local publication is verified; Composer's runtime floor will not be
  raised for the parser.
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

## Implementation handoff — 2026-07-12

The fork is at `C:\Users\caleb\AndroidStudioProjects\orgmode-kmp`. Its JVM test
suite passes on Windows with Android SDK 36:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :orgmode:jvmTest --no-daemon --no-configuration-cache --console=plain
```

Both the full and narrowed Maven-local publication commands stalled before
creating an artifact. Publication was coupled to the `copyASTViz` Python demo
through `dokkaHtml`. The direct `dependsOn("copyASTViz")` has now been removed
from the fork's `orgmode/build.gradle.kts`; the explicit `copyASTViz` task is
still available and normal Dokka API documentation remains attached to the
publication. This fork edit is intentionally uncommitted pending verification.

Resume with these bounded steps:

1. In the fork, run the narrowed publication first:

   ```powershell
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   .\gradlew.bat :orgmode:publishJvmPublicationToMavenLocal :orgmode:publishKotlinMultiplatformPublicationToMavenLocal --no-daemon --no-configuration-cache --console=plain
   ```

2. Confirm the artifact exists beneath
   `$HOME\.m2\repository\com\calebc42\orgmode` and use `javap -verbose` on a
   class in the JVM jar. Java 17 must report class-file major version 61.
3. In Composer, add `mavenLocal()` for local development, change the dependency
   to `implementation("com.calebc42.orgmode:orgmode:0.4.1-jetpacs.1")`, and
   change the composite substitution module to the same group and artifact.
4. Remove `@Ignore` from `OrgModeKmpShadowParserTest`, then run Composer's full
   test suite once from Maven Local and once with
   `-PorgmodeKmpPath=C:\Users\caleb\AndroidStudioProjects\orgmode-kmp`.
5. Commit the verified publication fix in the fork. Do not commit a mutable
   Maven-local dependency as the final CI strategy; publish the pinned fork
   release before O1 is declared complete.
6. Begin O2 with a fixture-corpus parity report covering parse success and
   exact reconstruction. Keep `OrgCodec` authoritative while differences are
   catalogued; semantic `AppSpec` cutover belongs after that inventory.
