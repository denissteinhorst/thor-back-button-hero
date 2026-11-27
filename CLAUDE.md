# CLAUDE.md

Guidance for Claude Code in this repository.

**Read [`AGENTS.md`](AGENTS.md) first.** It holds the shared rules for every coding agent: the
working agreement, architecture invariants, licensing, the gate and the definition of done. This
file only adds what is specific to working with Claude and the owner's machine. All technical
knowledge lives in [`docs/`](docs/README.md); start at its index. Where prose disagrees,
`AGENTS.md`, executable configuration and current source win.

## Talking to the owner

- The owner writes in German. Answer in German; code, comments, commits and repository docs stay
  English.
- **Ask questions directly in chat, with numbered answer choices (1–n), and wait for the reply.**
  Never use `AskUserQuestion` or any other input menu or asynchronous question tool; the owner
  overlooks them. With several open decisions ask one question per message, and always end the
  choices with a free-text option.
- "In den Docs" means a Markdown file in `docs/` (English), never a Claude Docs document.
- The pace rules (no intermediate questions under a mandate, no "continue?" between steps, the
  owner tests only at the end, no browser checks of the website) are in
  [`AGENTS.md`](AGENTS.md#pace-and-autonomy).
- You are the senior developer, the owner is the project owner. Surface consequential decisions
  (behavior, compatibility, UX, stored data, architecture, security, releases) instead of making
  them. Routine, reversible implementation details within an agreed direction are yours.
- When work touches an area with known open points, mention them and propose same-area synergies;
  never expand scope silently.

## The owner's machine

- **JDK 17 is not on `PATH`.** `./tools/verify.sh` and the git hooks find it themselves. For any
  other Gradle call pass it explicitly:

  ```sh
  JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew <task> --console=plain
  ```

- `ANDROID_HOME` is not set; the SDK path comes from `local.properties`
  (`/opt/homebrew/share/android-commandlinetools`).
- While iterating, run only the affected test classes (`./gradlew testDebugUnitTest --tests '<Class>'`
  with the JDK above, plus lint or a fixture re-record when the change calls for it). Run
  `./tools/verify.sh` once per finished change, before handing it over for acceptance, not after every
  fix. The pre-commit hook runs the full JVM suite anyway. Never bypass hooks.
- There is no emulator image. Device work needs the physical AYN Thor over `adb`; re-check the serial
  with `adb devices` every time.

## Device workflow

1. After implementing and locally verifying a change, build and install the newest debug build
   yourself. Do not hand the owner an install task unless device access is unavailable.
2. After every install, restore the prerequisites (notification permission, accessibility service
   with the fully qualified component, `accessibility_enabled`) and confirm through
   `dumpsys accessibility` that the service is **bound**. Commands and device facts (pad clone,
   display ids, RetroArch) are in [docs/DEVICE-TEST-SUITE.md](docs/DEVICE-TEST-SUITE.md#after-every-install-restore-the-prerequisites).
3. Run `python3 tools/devicetest/run.py --changed` for the areas the change affects; `--full` only
   before a push or on request. Scale manual checks to the risk tier in
   [docs/MANUAL-TESTING.md](docs/MANUAL-TESTING.md). Docs, build-config and test-only changes need no
   device pass.
4. Prefer logs, tests, `adb` state and the owner's own description over screenshots. Capture or
   inspect screenshots only when nothing better works, and only after asking.
5. Hand over with a clearly separated section headed **Test task for the project owner**: one
   sentence on what is being tested, a focused checklist for the change, and targeted regression
   checks for what it could affect. Not the full manual suite.

## Commits and releases

- Commit only after the change is complete and verified, including the owner's device test. One
  clean, focused commit per change; untracked or modified files you did not create belong to the
  owner and stay out of it.
- Never push. Never tag or publish a release until the owner announces release preparation; then
  follow [docs/BUILD-AND-RELEASE.md](docs/BUILD-AND-RELEASE.md#releasing).

## Where to look

| Need | Document |
|---|---|
| Reading path for newcomers, every document | [docs/README.md](docs/README.md) |
| How the code is divided and how a press flows | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| What each file is | [docs/PROJECT-STRUCTURE.md](docs/PROJECT-STRUCTURE.md) |
| Gates, expected numbers, ratchets, environment traps | [docs/TESTING.md](docs/TESTING.md) |
| Setup, recipes, dependencies, releases, the signing key | [docs/BUILD-AND-RELEASE.md](docs/BUILD-AND-RELEASE.md) |
| D-pad root helper (read before touching it) | [docs/HELPER.md](docs/HELPER.md) |
| Bottom Recents styling, AYN's system Recents | [docs/THOR-SYSTEM-RECENTS.md](docs/THOR-SYSTEM-RECENTS.md) |
