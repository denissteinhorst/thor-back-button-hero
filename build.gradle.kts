buildscript {
    dependencies {
        // AGP 9 bundles Kotlin support but defaults to an older KGP; pin the version we build with.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.3" apply false
}

tasks.register<Exec>("installGitHooks") {
    description = "Points git at .githooks so the hooks work right after a clone."
    commandLine("git", "config", "core.hooksPath", ".githooks")
    isIgnoreExitValue = true
}

// The device harness's own logic (selection, recovery, trace/visual comparison) runs on synthetic
// state, so it belongs in the routine gate; the device suite itself stays separate.
tasks.register<Exec>("hostTests") {
    group = "verification"
    description = "Device-harness host tests (no adb, no device)."
    workingDir = rootDir
    commandLine("python3", "tools/devicetest/host_tests.py")
}

tasks.register("verify") {
    group = "verification"
    description = "Single gate: lint, unit tests, harness host tests, debug+release build, androidTest compile."
    dependsOn(
        "hostTests",
        ":app:lintDebug",
        ":app:testDebugUnitTest",
        ":app:jacocoCoverageVerification",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":app:assembleRelease",
    )
}
