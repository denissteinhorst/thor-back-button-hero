# Dual mode

Dual mode turns the pad's Home key into a second modifier with its own complete set of bindings.
Back + X and Home + X can then run different actions. This document records the owner's decisions
(2026-11-20) and how they map onto the code.

## Behaviour

| Topic | Decision |
|---|---|
| Activation | Settings → Home button: *Dual mode*. It excludes *Use the Home button as Back*; switching either on switches the other off. |
| Home layer | Everything Back offers: the four gestures (single, double, tap-and-hold, hold), Home + every shoulder, face, Start/Select button, D-pad direction, stick direction and stick click. |
| Timings | The Home layer has its own gesture timings (double tap, tap-and-hold, hold) and its own stick-click timings. |
| First activation | The Back layer is copied into the Home layer once, except Home's single tap, which becomes Home. Later activations bring back the stored Home layer. |
| Deactivation | Home layer bindings stay stored but are ignored; Home behaves as without the app again. |
| Both modifiers held | Whichever goes down first owns the press; the other one is ignored until both are up (the Home-as-Back rule). Back + Home is therefore shown as unavailable in dual mode, and the Home layer has no Home + Home row. |
| App exceptions | One set for both layers: an excepted control is excepted under Back and Home alike. |
| Activation shortcut | Unchanged: it is a pair of buttons held without a modifier, so it works with either. |
| Emergency release | Home + Back for 5 s still returns the pad to Android in dual mode (unlike Home as Back). |
| Settings pages | Two columns, Home left and Back right, under a header with both icons and a rule; every binding pair starts on the same line, and the shorter side leaves its space empty. Also at compact width. |
| Overview | A Home / Back switch above the drawing chooses which layer it shows. |
| Unbind / reset | In dual mode the app asks first: only Home, only Back, or both. The whole-app factory reset switches dual mode off and clears the Home layer. |
| Backup | Saves and restores the mode, the Home layer bindings and its timings. Older backups load with an empty, unseeded Home layer. |

## Storage

The Home layer reuses the Back layer's schema under the `dual_` prefix: every per-binding key
`<prefKey>_<field>` exists as `dual_<prefKey>_<field>` (for example `dual_l1_package`), and the timings
are `dual_double_ms`, `dual_tap_hold_ms`, `dual_hold_ms` and `dual_<stick>_double_ms` /
`dual_<stick>_tap_hold_ms`. `dual_mode` is the switch and `dual_seeded` records that the one-time copy
happened. In code a Home layer binding is a `HomeLayerButton` wrapping the Back layer's `ComboButton`;
everything keyed by `prefKey` works on it unchanged, and type decisions use `ComboButton.base`.

## Runtime

`HomeAsBack` already gives one key ownership of a press and swallows the other. In dual mode the
owning Home press is translated to Back like in Home-as-Back mode, but drives a second
`BackGestureMachine` that reads the Home layer's gestures and timings, and every combo pressed meanwhile
resolves to its `HomeLayerButton`. An excepted Home gesture delivers Android's Home instead of Back.
`PadHelper` sends `HOMEBACK 2`, which makes the root helper treat Home as a modifier for D-pad and stick
captures while keeping the emergency release.

## Tests

JVM tests cover the storage, seeding, exclusivity, plans, backup and the two-column pages (goldens). The
device suite runs every layer-aware test once per layer on `--full` (and with `--layers home`, or on
`--changed` when the change touches dual mode), pressing Home instead of Back and binding the `dual_`
keys, plus the dedicated `dual` area; see `docs/DEVICE-TEST.md`.
