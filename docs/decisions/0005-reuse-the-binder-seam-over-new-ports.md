# 0005 — Reuse the binder seam instead of new platform ports

- **Status:** accepted
- **Date:** 2026-11-23
- **Owner decision:** delegated; root commands and fallbacks must not change

## Context

Privileged work goes through `PService` from 13 files. The tests already replace AYN's daemon at the
Binder level (`support/FakePService`): every root command, generated script and failure reply is
observable on the JVM without touching a call site. The bottom-screen rule, on the other hand, was
written out twice, in the service and in the frontend parking.

## Decision

Keep `PService` as the root boundary with its exact semantics (stdout or `null`, `""` for an empty
reply, cached success, 10 s retry, one command at a time) and do not wrap it in a `RootShell`
interface. Add a platform helper only where logic is duplicated: `core.Displays` is the single
definition of the bottom screen and of a private display. Keep direct construction; no composition
graph.

## Consequences

Call sites and generated commands stay byte-identical, frozen by the command fixtures and
`PServiceFailureContractTest`. A future port needs a concrete test or duplication it removes, not
the wish for an interface. The helper and panel sockets still accept only uid 0; tests for that
remain due before their connection code changes.

## Alternatives considered

A `RootShell` interface with a fake would add a second indirection over the same Binder seam and
risk drifting from the real transaction contract. Wrapping every Android service (settings, tasks,
media) would move code without new test reach.
