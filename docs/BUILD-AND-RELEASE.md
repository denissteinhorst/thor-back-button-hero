# Build and maintenance guide

Everything needed to set the project up, change it safely, and ship a release.
Companion documents: [`ARCHITECTURE.md`](ARCHITECTURE.md) for how the code is divided,
[`TESTING.md`](TESTING.md) for the gates, [`PROJECT-STRUCTURE.md`](PROJECT-STRUCTURE.md)
for what each file is.

## Setting up a machine

```sh
brew install openjdk@17                        # keg-only; the gate finds it itself
brew install --cask android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0" "build-tools;36.0.0" "platform-tools" "ndk;29.0.14206865"

git clone git@github.com:denissteinhorst/thor-back-button-hero.git
cd thor-back-button-hero
echo "sdk.dir=$(brew --prefix)/share/android-commandlinetools" > local.properties
./tools/verify.sh                              # also installs the git hooks
```

`local.properties` is gitignored and therefore never part of a clone. Without it — and
without `ANDROID_HOME` — AGP cannot locate the SDK. Expect the first run to be slow while
Gradle downloads dependencies.

**JDK 17 is not on `PATH`.** A bare `java -version` fails; the keg-only JDK lives at
`/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`. `tools/verify.sh` and
the git hooks resolve it themselves. For one-off Gradle calls, pass it:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
  ./gradlew <task> --console=plain
```

There is **no emulator image**, so any instrumented test needs the physical AYN Thor
attached over `adb` (the device id changes — check with `adb devices`).

## Everyday commands

```sh
./tools/verify.sh                 # THE gate: lint, tests, coverage, both builds, androidTest compile
./gradlew assembleDebug           # debug APK
./gradlew installDebug            # build + install on the attached device
./gradlew assembleRelease         # release APK (unsigned locally - see below)
./gradlew connectedDebugAndroidTest   # the one instrumented test; needs a device
python3 tools/docs/check-links.py      # every relative link and anchor in the docs resolves
python3 tools/docs/check-structure.py  # PROJECT-STRUCTURE.md names every production file
```

Run `./tools/verify.sh` after every change. Never bypass the hooks with `--no-verify`.

Note that `assembleRelease` locally produces `app-release-unsigned.apk`, which Android
**refuses to install**. That is expected: signing comes from environment variables that
only CI sets. Measure and test on the debug build; read APK *size* off the release
artifact without installing it.

Debug builds additionally bundle LeakCanary, which adds a second "Leaks" launcher icon
and a few MB. Neither appears in a release build.

Automated UI runs that call `uiautomator dump` (or connect any other `UiAutomation`)
make Android 13 unbind every accessibility service and bind it again, so each dump
destroys and recreates `SwitcherService` — and restarts the pad helper with it.
LeakCanary then reports `AccessibilityService$IAccessibilityServiceClientWrapper.mContext`
holding the destroyed instances. That binder stub is released once system_server
collects its side (LeakCanary later logs "All retained objects have been garbage
collected"); it is an artifact of the automation, not a leak in normal use.

## Changing things safely

The app's features, layout and wording are **final**. The suite exists to keep them that
way, so most maintenance is either additive or mechanical. A few recipes:

### Changing a stored preference default or key

Do **not** just change the default — existing installs would silently shift behavior.
Add a guarded one-time migration to `Migrations.kt` (run from `Prefs`'s `init`, in order), following the existing
pattern: a versioned boolean flag that gates a single rewrite.

```kotlin
if (!sp.getBoolean("my_change_v1", false)) {
    // rewrite affected keys here
    sp.edit().putBoolean("my_change_v1", true).apply()
}
```

Then extend `PrefsMigrationTest` to cover both paths: a fresh install and an install that
already carried the old value.

When a default changes for a setting that existing users may never have touched (so no value is
stored), pin the old effective value for installs that already ran, and apply the new default only
to fresh installs. `unassigned_defaults_v1` in `Migrations.kt` is the model: when the defaults
became "nothing assigned", it pinned the earlier D-pad and on/off-shortcut defaults for every
install that already existed. A fresh install is marked as migrated by `markFreshInstall`, so add
the new flag there too. Test all three cases: fresh, update with the old value, update with a
custom value.

### Adding or changing a string

Edit **both** `app/src/main/res/values/strings.xml` and
`app/src/main/res/values-de/strings.xml`. `ResourceIntegrityTest` enforces parity — the
only legitimate gap is `app_name`, which is `translatable="false"`.

Any visible text change will turn settings goldens red. That is the suite working
correctly; re-record only if the change is intended (see [`TESTING.md`](TESTING.md)).

### Adding a button action

Actions are stored as raw strings: either a bare package name (launch that app) or an `@`-prefixed
sentinel. The stored strings are a frozen contract ([ADR 0001](decisions/0001-frozen-external-contracts.md)),
so a new action adds a sentinel and never renames one. Touch every consumer:

1. `prefs/Action.kt`: the sentinel and its `ActionCodec` mapping (raw string to typed `Action`).
2. `actions/ActionGroups.kt`: the chooser group it appears in.
3. `settings/BindingDescriber.kt`: its label, description and option visibility; label strings in
   both `values/strings.xml` and `values-de/strings.xml`.
4. `SwitcherService.runComboAction`: the dispatch, or a handler in `actions/` when the action has
   its own logic or state.
5. Tests: `BindingTextTableTest` (its frozen text tables grow by the new rows) and the dispatch
   test of the handler; the settings goldens change only if a row's look changes.

### Touching gesture or overlay code

`SwitcherService`, `SwitcherOverlay`, `AccessibilityAppSource`, `CardTouch` and
`BackGestureMachine` are where a green gate proves the least: the overlay's and the service's
live behavior (a real overlay window, a live accessibility connection, physical keys) is not
covered at all, only their construction and extracted decisions. These changes are tier S2 or S3 — always follow up with the
corresponding device procedure in [`MANUAL-TESTING.md`](MANUAL-TESTING.md), and use the
gesture-trace comparison there if the change is meant to be behavior-neutral.

### Debugging gesture timing

`SwitcherService.DEBUG_GESTURES` is a compile-time constant, off by default. Flip it to
`true`, build, and `adb logcat` will trace every DOWN/UP decision. **Set it back to
`false` before committing** — left on it logs on every single Back press.

## Dependency updates

Dependabot runs weekly for Gradle dependencies and GitHub Actions. Treat the two kinds
very differently:

**Security alerts** (`dependabot/alerts`) are real and should be acted on. Check with:

```sh
gh api repos/denissteinhorst/thor-back-button-hero/dependabot/alerts --jq 'length'
```

**Version-update PRs** are opened merely because a newer version exists, with no security
implication. Judge them on risk:

| Kind | Risk | Approach |
|---|---|---|
| GitHub Actions (`actions/*`, `softprops/*`) | Low — CI only, never in the APK | Take them; CI proves itself |
| Test-only libraries (Robolectric, Espresso, UiAutomator, JUnit) | Medium — can shift snapshot or qualifier behavior | One at a time behind a green gate |
| Kotlin, AGP, Gradle | **High** — these affect the shipped artifact and can change behavior | One at a time, green gate **plus** a full device pass, never alongside a release |

**Robolectric stays on 4.17 for now.** 4.18 fails 89 tests on SDK 34 with
`NoClassDefFoundError: android/companion/virtual/VirtualDeviceManager$VirtualDeviceListener`,
and running the suite on SDK 35 changes the framework output the goldens freeze. Lint's
`NewerVersionAvailable` hint for it is therefore listed in `app/lint-baseline.xml` (20 entries
now). When a newer Robolectric appears, try it with `sdk=34` and drop that baseline entry along
with the version bump; never answer it by re-recording goldens.

Keep test and debug tooling in `testImplementation`/`androidTestImplementation`/
`debugImplementation` only. The 0BSD licence rests on the release classpath containing
nothing but the Kotlin stdlib. Verify any time:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | \
  grep -iE "junit|robolectric|espresso|uiautomator|leakcanary|jacoco"   # must print nothing
```

Dependabot compares against the default branch, so after a local bump it can propose a version
already in use; check the build files before merging.

Note that GitHub Actions are restricted to GitHub-owned, verified publishers, plus
`softprops/action-gh-release`. A new third-party action has to be allowed explicitly.

## Releasing

Versioning is semver. Two numbers must move together:

- **`versionName`** (e.g. `1.2.0`) is the semver string and **is the tag**: `v1.2.0`.
  `UpdateChecker` compares the tag against the installed `versionName`.
- **`versionCode`** (an integer) must **also** increase. Android uses it to order
  upgrades and refuses to install a lower one. It no longer appears in the tag.

### Steps

1. Decide the new version. Bump **both** `versionCode` and `versionName` in
   `app/build.gradle.kts`.
2. Re-record the settings goldens, since the version line is part of them, and confirm
   the diff contains nothing else:
   ```sh
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     UPDATE_GOLDENS=1 ./gradlew :app:testDebugUnitTest --rerun-tasks
   git diff app/src/test/resources/golden/
   ```
3. `./tools/verify.sh` green, commit, push.
4. **Wait for CI to be green on that exact commit.** A green local gate is not enough.
5. Tag and push:
   ```sh
   git tag -a v1.2.0 -m "Back Button Hero 1.2.0" <commit>
   git push origin v1.2.0
   ```
6. `release.yml` then verifies the tag matches `versionName`, builds and signs the APK,
   and publishes `thor-back-button-hero-v1.2.0.apk`. Watch it:
   `gh run watch $(gh run list --limit 1 --json databaseId --jq '.[0].databaseId')`
7. Verify the published artifact rather than trusting the workflow status:
   ```sh
   gh release download v1.2.0 -D /tmp
   JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
     /opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/apksigner \
     verify --print-certs /tmp/thor-back-button-hero-v1.2.0.apk
   ```
   The certificate SHA-256 must match every previous release.

If something is wrong and nobody has downloaded the APK yet, delete the release and the
tag, fix, and re-tag — `gh release delete v1.2.0` and
`git push origin :refs/tags/v1.2.0`.

### Release workflow details

`.github/workflows/release.yml` fires on `v*` tags. It fails fast unless the tag is exactly
`v<versionName>`, because a mismatch would make every installed app miss the release for good. It
decodes `RELEASE_KEYSTORE_BASE64`, builds the signed APK from the `RELEASE_KEYSTORE_PATH`,
`RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD` environment variables,
publishes `thor-back-button-hero-v<versionName>.apk` and `app-simulation.html` with the text of
`docs/release-notes/v<versionName>.md`, then deletes the keystore. Signing is environment-only by
design; no keystore is ever committed (`*.keystore` and `*.jks` are gitignored).

The first two releases were tagged `v1` (1.0.0) and `v2` (1.0.1) under an older
`v<versionCode>` scheme. `parseVersion` still reads short forms (`v1` is `1.0.0`), but a build
installed before the switch carries the old integer-only parser and needs one manual update.

### The signing key

Android refuses to update an installed app unless the new APK carries the **same** signature. The
keystore and its password are therefore the only irreplaceable artifacts in the project: lose them
and no future version can replace an installed copy; every user would have to uninstall and lose
their settings. GitHub secrets are write-only and cannot be read back.

What must exist in a backup (password manager or encrypted backup, never in the repository or in
plaintext cloud storage):

| Artifact | Detail |
|---|---|
| `release.keystore` | created 2026-10-24, 4374 bytes, format PKCS12 |
| its password | one password for both store and key (PKCS12) |
| alias | `thor-back-button-hero` |

A working copy may sit at the repository root (gitignored), but nothing reads it from a fixed path:
CI takes the key from `RELEASE_KEYSTORE_BASE64`, a local signed build from the environment.

**Releasing does not need the keystore on disk.** As long as the four secrets exist, tagging keeps
producing signed releases, also on a freshly set-up machine.

**If the secrets are gone** (repository recreated, secrets deleted), restore them from the backup:

```sh
base64 -i /path/to/backup/release.keystore | gh secret set RELEASE_KEYSTORE_BASE64
gh secret set RELEASE_KEY_ALIAS -b "thor-back-button-hero"
gh secret set RELEASE_KEYSTORE_PASSWORD   # prompts; same password for both
gh secret set RELEASE_KEY_PASSWORD
gh secret list                            # expect all four
```

**To build a signed APK locally** (rarely needed):

```sh
RELEASE_KEYSTORE_PATH=/absolute/path/to/release.keystore \
RELEASE_KEYSTORE_PASSWORD='…' \
RELEASE_KEY_ALIAS=thor-back-button-hero \
RELEASE_KEY_PASSWORD='…' \
./gradlew assembleRelease --console=plain
```

Without `RELEASE_KEYSTORE_PATH` the signing config is skipped and the build produces
`app-release-unsigned.apk`; that is expected.

**To confirm a restored key**, compare certificate fingerprints (`ANDROID_HOME` is not set on the
owner's machine; the SDK path comes from `local.properties`):

```sh
/opt/homebrew/share/android-commandlinetools/build-tools/34.0.0/apksigner \
  verify --print-certs some-release.apk
```

The SHA-256 digest must equal that of earlier releases. A different digest means the wrong key;
publishing with it would break updates for everyone. The release certificate (PKCS12, RSA 4096,
alias `thor-back-button-hero`, created 2026-10-24) has these fingerprints:

```
SHA-256: 7f8ab05139a406a3d50ba6267c60228b90935f4c02f854c26d69cf1b275df3ee
SHA-1:   3a119829715ba71603e12657dce45db10b98a632
```

The keystore and its password live only in the owner's backup; GitHub secrets are write-only and
cannot be read back.

### The repo must stay public

`UpdateChecker` calls the Releases API unauthenticated and carries no token by design.
Against a private repo that returns 404, so the update check would fail permanently for
every user — including the author, since the app sends no credentials. The in-app
repository link and the published APK assets depend on public visibility too.

Also note: unauthenticated GitHub API calls are limited to **60 per hour per IP**. A `403`
with `rate_limit.core.remaining == 0` is throttling, not a code problem — check
`https://api.github.com/rate_limit` before concluding anything.

## Repository protection

Configured as rulesets rather than classic branch protection:

- **`protect-main`** — pull request with one approval required, stale reviews dismissed,
  last-push approval required, the `verify` status check required, force-pushes and branch
  deletion blocked. Repository admins and maintainers bypass, so the author can still push
  directly.
- **`protect-release-tags`** — `v*` tags cannot be deleted, moved or force-updated
  (admin bypass).
- Fork pull requests require approval before any workflow runs, so outside contributors
  cannot start a pipeline unattended.
- `GITHUB_TOKEN` defaults to read-only; the release job requests `contents: write`
  for itself alone.
- Secret scanning and push protection are on, as are Dependabot security updates.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `Unable to locate a Java Runtime` | JDK 17 is not on `PATH`. Use `./tools/verify.sh`, or pass `JAVA_HOME` explicitly. |
| AGP cannot find the SDK | `local.properties` is missing (it is gitignored). Recreate it as shown above. |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | You tried to install the locally built, unsigned release APK. Install the debug build, or download a signed APK from a release. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signature mismatch — e.g. installing a release APK over a debug build. Uninstall first. |
| CI red, local green | Most likely an environment-dependent lint finding. Read the CI log rather than assuming parity. |
| Coverage report shows 0 % everywhere, build green | `isIncludeNoLocationClasses` or `enableUnitTestCoverage` is missing. |
| A golden test fails after a production change | The change altered visible output. That is the suite working — do not re-record unless the change was intended. |
| Update check always fails on the device | Expected before the first release exists, and after a reboot before the network is up. The check is one attempt per app open with no retry. |
| `am start -W` reports `HOT`/`WARM`, never `COLD` | The accessibility service keeps the process alive; `force-stop` cannot kill it. Disable the service in Android settings for a true cold-start measurement. |
| `run-as: package not debuggable` | You are inspecting a release build. Correct behavior — use a debug build for `run-as`. |
