buildscript {
    dependencies {
        // AGP 9 bundles Kotlin support but defaults to an older KGP; pin the version we build with.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}

tasks.register<Exec>("installGitHooks") {
    description = "Points git at .githooks so the hooks work right after a clone."
    commandLine("git", "config", "core.hooksPath", ".githooks")
    isIgnoreExitValue = true
}

tasks.register("verify") {
    group = "verification"
    description = "Single gate: lint, unit tests, debug+release build, androidTest compile."
    dependsOn(
        ":app:lintDebug",
        ":app:testDebugUnitTest",
        ":app:jacocoCoverageVerification",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        ":app:assembleRelease",
    )
}
