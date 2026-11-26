# 0002 — One module, framework Views, no new dependencies

- **Status:** accepted
- **Date:** 2026-11-22
- **Owner decision:** delegated within the owner frame (0BSD, unchanged look)

## Context

The app has roughly 20,000 lines in one package. Its UI is hand-built Views, protected by text
snapshots of the View tree. The release APK depends only on `kotlin-stdlib` and
`org.jetbrains:annotations`. Every additional runtime library adds licence obligations to a
project-owned codebase that is distributed under 0BSD.

## Decision

Keep one Gradle module and organise it in packages by feature. Keep framework Views, with no
Compose and no layout XML. Wire dependencies explicitly through constructors where there is a
real test seam, without a DI library. Add no third-party runtime, analysis or build plugin. A
version catalog and an in-repo metrics report are optional and only worth adding when they show a
benefit (both were skipped in R0, because each version appears once and one package has no edges
to measure).

## Consequences

The goldens can still prove the UI unchanged, and the dependency check (A9) stays exact. Package
boundaries remain conventions plus review: within one module, `internal` does not isolate packages.

## Observed after the refactoring

Held: the release classpath is still `kotlin-stdlib` plus `annotations`, and every extraction (routers, describers, recorder mix, helper protocol) is plain Kotlin in the one module.

## Alternatives considered

Multi-module builds enforce boundaries but add build overhead this size does not need. Compose or
a DI framework would add runtime dependencies and would require a UI rewrite that the snapshots
could not prove equivalent.
