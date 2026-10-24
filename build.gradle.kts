plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("verify") {
    group = "verification"
    description = "Single gate: lint, unit tests, debug+release build."
    dependsOn(
        ":app:lintDebug",
        ":app:testDebugUnitTest",
        ":app:assembleDebug",
        ":app:assembleRelease",
    )
}
