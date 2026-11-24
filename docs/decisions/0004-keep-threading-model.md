# 0004 — Keep the threading model

- **Status:** accepted
- **Date:** 2026-11-22
- **Owner decision:** delegated; gesture timing must not change

## Context

Gesture decisions depend on the exact order and delay of `Handler` callbacks, and several helpers
own their own threads and sockets. The trace oracle and the threshold tests freeze this timing.

## Decision

Do not introduce coroutines and do not consolidate executors. Ownership of callbacks and resources
may be made explicit, but each queue, delay, concurrency relation and shutdown order stays as it is.
Reuse `GestureClock` and `GestureScheduler` where they fit. A lifetime helper is added only for a
demonstrated need.

## Consequences

No new runtime dependency is needed, and the timing evidence stays valid. Lifecycle work in R10 has
to prove that queues and order are preserved, not just that work still runs on the main thread or
a background thread.

## Observed after the refactoring

Held: R10 kept every lane (see ARCHITECTURE "Lifetimes and work lanes"); the only lifecycle change shares the identical unbind/destroy release.

## Alternatives considered

`kotlinx-coroutines` would be a new Apache-2.0 runtime dependency and would change the dispatch
semantics the traces protect. A shared executor would merge queues whose separation is load-bearing.
