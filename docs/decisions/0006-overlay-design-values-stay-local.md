# 0006 — Overlay design values stay with their overlay

- **Status:** accepted
- **Date:** 2026-11-24
- **Owner decision:** delegated (refactoring plan R7: tokens are extracted, never harmonized)

## Context

The refactoring plan proposed a shared token file for the overlays' colours, dimensions, text
sizes and radii. An inventory of `NoticeOverlay`, `FocusIndicatorOverlay`,
`RecordingIndicatorOverlay`, `DpadOverlay`, `SwitcherOverlay` and the `Recents*` styling classes
found every such literal used exactly once. The bottom switcher's colours come from the installed
launcher at runtime (`RecentsTheme`, `RecentsBadges`), not from constants. Merging near-equal values
(for example the 20 dp pictogram margin and the 17 dp recording-dot margin) would be a visible change.

## Decision

No central token file. Each overlay keeps its design values as named constants next to the code
that uses them. What the indicators genuinely share, the top-display window context and the
nine-point position grid, lives in `overlay/TopOverlayWindow`. A value moves into shared code only
when a second consumer needs the same value with the same meaning.

## Consequences

An overlay's look is readable in one file. The overlay goldens (`OverlaySnapshotTest`,
`SwitcherSnapshotTest`) and the settings visual baseline keep protecting the values; changing a
shared helper shows up in every golden that depends on it.

## Observed after the refactoring

Held: R7 extracted only `TopOverlayWindow` (shared window context and position grid); every colour and size stayed beside its overlay, and the overlay goldens did not change.

## Alternatives considered

A `ui/Tokens.kt` with one name per existing literal: every name would have exactly one user, which
adds indirection without a shared design rule. Harmonizing values into a smaller set was ruled out
as a visual change that needs an explicit owner request.
