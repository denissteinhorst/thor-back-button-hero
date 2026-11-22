# 0001 — External contracts are frozen

- **Status:** accepted
- **Date:** 2026-11-22
- **Owner decision:** required and given (look, behavior and stored data stay identical)

## Context

Several names in the code are read by something other than the compiler. Android keeps
`dev.steinhorst.backbuttonhero.SwitcherService` in every user's `enabled_accessibility_services`.
Root helpers are started by class name through `app_process`, sometimes from a string built at
runtime. Other processes match binder descriptors and broadcast actions, and the device harness
parses log texts. Stored preferences and backups outlive any version. A rename can compile and
still disable the app or lose data on installed devices.

## Decision

The following stay unchanged: the manifest components, the ten root entry points (they stay in
the root package, though their logic may move behind them), wire strings, the `back_button_hero`
preferences file with every key, encoding, migration flag and its order, the backup format, media
locations, resources, the native helper and the release runtime classpath. `isMinifyEnabled` stays
false because class names are part of this contract.

## Consequences

Packages can be reorganised around these fixed points. `tools/contract/check.sh` enforces the
contract mechanically (A1–A11), and `tools/contract/drills.sh` shows that it catches moves,
renames and lost entry points. Fixtures freeze stored data and root commands. Adding a reviewed
line to `tools/contract/renames.txt` is the only way to rename a frozen test or ratchet.

## Alternatives considered

Moving the entry points with a compatibility shim would still break the stored service component
and the runtime-built names, and would add code that exists only to forward calls.
