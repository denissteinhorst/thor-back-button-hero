# Documentation index

All project documentation lives in this folder. The repository root keeps only `README.md` (the
user-facing product page), `AGENTS.md` (rules for every coding agent) and `CLAUDE.md` (additions
for Claude Code).

## Contents

- [Reading path for newcomers](#reading-path-for-newcomers)
- [Understanding the app](#understanding-the-app)
- [Changing and verifying it](#changing-and-verifying-it)
- [Building, releasing and the website](#building-releasing-and-the-website)
- [Reference and history](#reference-and-history)
- [Architecture decisions](#architecture-decisions)
- [KDoc and comment conventions](#kdoc-and-comment-conventions)

## Reading path for newcomers

1. [`../README.md`](../README.md): what the app does for its users. Features, wording and look are
   final; the code exists to keep them exactly as described there.
2. [`../AGENTS.md`](../AGENTS.md): the working rules and invariants that apply to every change.
3. [ARCHITECTURE.md](ARCHITECTURE.md): the two entry points, the packages, how a Back press
   travels through the code, and the external contracts that must never change.
4. [PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md): find the file for a concern.
5. [BUILD-AND-RELEASE.md](BUILD-AND-RELEASE.md): set up a machine and run the gate.
6. [TESTING.md](TESTING.md): what the gate proves, what green looks like, and where only the device
   can tell.
7. Before touching a device-only area, its deep dive: [HELPER.md](HELPER.md) (D-pad),
   [THOR-SYSTEM-RECENTS.md](THOR-SYSTEM-RECENTS.md) (bottom Recents),
   [HOME-DUAL-MODE.md](HOME-DUAL-MODE.md) (Home layer).

## Understanding the app

| Document | Purpose |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Components, how a gesture travels through them, display targeting, panels, Recents routing, rejected approaches. |
| [PROJECT-STRUCTURE.md](PROJECT-STRUCTURE.md) | File-by-file map of the repository. |
| [HELPER.md](HELPER.md) | The root input helper behind Back + D-pad; mandatory reading before touching that path. |
| [HOME-DUAL-MODE.md](HOME-DUAL-MODE.md) | Home as a second modifier with its own bindings, and the owner's decisions behind it. |
| [THOR-SYSTEM-RECENTS.md](THOR-SYSTEM-RECENTS.md) | The AYN launcher's system Recents and how the bottom Recents copies it. |
| [FAQ.md](FAQ.md) | Reader-facing answers: compatibility, updates, privacy and trust. |

## Changing and verifying it

| Document | Purpose |
|---|---|
| [TESTING.md](TESTING.md) | Test concept, every automated gate, expected results, and what is deliberately not automated. |
| [FEATURE-COVERAGE.md](FEATURE-COVERAGE.md) | Every observable behavior mapped to the check that would notice a change. |
| [MANUAL-TESTING.md](MANUAL-TESTING.md) | Risk tiers and step-by-step device procedures. |
| [DEVICE-TEST-SUITE.md](DEVICE-TEST-SUITE.md) | The on-device suite in `tools/devicetest`, post-install prerequisites, traces, measurements. |
| [PERFORMANCE.md](PERFORMANCE.md) | Measured hot-path costs, rejected optimizations, resource budgets and how to measure. |

## Building, releasing and the website

| Document | Purpose |
|---|---|
| [BUILD-AND-RELEASE.md](BUILD-AND-RELEASE.md) | Setup, everyday commands, change recipes, dependencies, releases, the signing key, repository protection, troubleshooting. |
| [WEBSITE.md](WEBSITE.md) | The GitHub Pages site, the App-Simulation, the 2.0 film and the 3D model editor. |
| [release-notes/](release-notes/) | Release text per version; the release workflow and the landing page read it. |

## Reference and history

| Item | Purpose |
|---|---|
| [REFACTORING-VERIFICATION.md](REFACTORING-VERIFICATION.md) | Final verification of the 2026-11 structural refactoring against the pre-refactoring baseline. |
| [app-simulation.html](app-simulation.html) | The self-contained App-Simulation (clickable device diagram). |
| [check-simulation.mjs](check-simulation.mjs) | Consistency check for the App-Simulation. |
| [assets/](assets/) | Image masters for the docs and the app identity. |

## Architecture decisions

Consequential decisions are recorded in [decisions/](decisions/) as numbered records (context,
status, decision, consequences, alternatives). Copy [the template](decisions/0000-template.md) for
a new one. Records are added when a decision is actually made, never to fill a quota. A replaced
record stays, marked superseded, with a link to its successor.

| Record | Decision |
|---|---|
| [0001](decisions/0001-frozen-external-contracts.md) | Class names, wire strings, stored data and packaging are frozen contracts. |
| [0002](decisions/0002-one-module-framework-views-no-new-dependencies.md) | One module with packages by feature, framework Views, no new dependencies. |
| [0003](decisions/0003-typed-domain-over-unchanged-storage.md) | Typed actions and settings over byte-identical stored data. |
| [0004](decisions/0004-keep-threading-model.md) | Keep handlers and threads as they are; no coroutines or executor consolidation. |
| [0005](decisions/0005-reuse-the-binder-seam-over-new-ports.md) | Reuse the binder seam instead of new platform ports. |
| [0006](decisions/0006-overlay-design-values-stay-local.md) | Overlay design values stay with their overlay; only real sharing moves to `overlay/TopOverlayWindow`. |

## KDoc and comment conventions

- **KDoc** goes on every top-level class, object and interface, and on every non-private function
  whose purpose its name and signature do not already make clear. Keep it short. Say what it is
  for, which thread or handler it runs on, which display (top or bottom) it targets where that
  matters, what invariants it keeps, and link the doc section with the deeper context. A KDoc that
  only restates the signature is noise. Leave it out.
- **Inline comments** explain *why*: an OEM quirk, a timing constraint, an Android pitfall. A
  one-line summary above a dense block is fine when it reads faster than the code. Never narrate
  obvious code.
- **Language:** code, comments and documents are English.
- Documents with more than three sections get a table of contents. Frozen historical records are
  not reformatted.
