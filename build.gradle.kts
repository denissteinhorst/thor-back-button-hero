plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
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
