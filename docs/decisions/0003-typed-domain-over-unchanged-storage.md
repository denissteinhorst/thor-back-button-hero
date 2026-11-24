# 0003 — Typed domain over unchanged storage

- **Status:** accepted
- **Date:** 2026-11-22
- **Owner decision:** delegated; stored bytes must not change

## Context

Actions are stored as strings: a bare package name launches an app, and an `@` prefix marks a
built-in action. Callers interpret these strings in many places, which invites typos and
inconsistent handling. `Prefs` already owns the schema, the migrations and atomic multi-key writes.

## Decision

Introduce typed values (for example a sealed action type) with a codec whose encoded strings are
byte-identical to today's. `Prefs` remains the only owner of the `back_button_hero` file and of
its transactions. Feature-specific views may sit on top of it, over the same owner and file.

## Consequences

Callers get compile-time checks, and stored data, backups and migrations stay compatible in both
directions. The stored-data fixtures and `StoredDataContractTest` must stay green without
re-recording. An unknown stored value must be kept, not dropped.

## Observed after the refactoring

Held: `StoredDataContractTest` and the backup fixtures stayed green without a re-record from R2 to R12.

## Alternatives considered

Keeping raw strings is simplest but leaves the scattered parsing. A new storage format would need
migrations and would break downgrade and backup compatibility.

## Implementation (R2)

`prefs/Action.kt` holds the sealed `Action` and `ActionCodec`, the only string mapping; the service
dispatch, the overview labels and the backup reader decode through it. Lists keyed by action-list
entries (groups, the app chooser, search keys) are not stored values and stay string-keyed. The
migrations moved verbatim to `Migrations.kt`, and the macro library to `MacroStore`, both behind
`Prefs` as the single file and transaction owner.
