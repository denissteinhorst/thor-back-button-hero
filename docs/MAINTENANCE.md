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
Add a guarded one-time migration in `Prefs`'s `init` block, following the existing
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
to fresh installs. `shoulder_defaults_v3` in `Prefs` does this for L2 and R2: an update keeps them
unassigned, a fresh install gets the screenshot and controller-focus shortcuts. Test all three
cases: fresh, update with the old value, update with a custom value.

### Adding or changing a string

Edit **both** `app/src/main/res/values/strings.xml` and
`app/src/main/res/values-de/strings.xml`. `ResourceIntegrityTest` enforces parity — the
only legitimate gap is `app_name`, which is `translatable="false"`.

Any visible text change will turn settings goldens red. That is the suite working
correctly; re-record only if the change is intended (see [`TESTING.md`](TESTING.md)).

### Adding a shoulder-button action

Shoulder actions are stored either as a bare package name (launch that app) or as an
`@`-prefixed sentinel. Add the new sentinel, handle it in `SwitcherService`, add its
label to both string files, and extend the shoulder-row goldens if the row's appearance
changes (for example whether the screen toggle shows).

### Touching gesture or overlay code

`SwitcherService`, `SwitcherOverlay`, `AccessibilityAppSource`, `CardTouch` and
`BackGestureMachine` are where a green gate proves the least — `SwitcherOverlay` is only
about a third covered. These changes are tier S2 or S3 — always follow up with the
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

Keep test and debug tooling in `testImplementation`/`androidTestImplementation`/
`debugImplementation` only. The 0BSD licence rests on the release classpath containing
nothing but the Kotlin stdlib. Verify any time:

```sh
./gradlew :app:dependencies --configuration releaseRuntimeClasspath | \
  grep -iE "junit|robolectric|espresso|uiautomator|leakcanary|jacoco"   # must print nothing
```

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

### The signing key

The keystore and its password are the only irreplaceable artifacts in the project. Lose
them and no future version can ever replace an installed copy — users would have to
uninstall first and lose their settings. GitHub secrets are write-only and cannot be read
back.

Releasing does **not** need the keystore on disk: CI signs from
`RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD`. The backup matters only if those secrets are ever lost. Full
recovery procedure, including restoring the secrets and verifying a restored key by
fingerprint, is in `CLAUDE.md` under "The signing key — backup and disaster recovery".

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
