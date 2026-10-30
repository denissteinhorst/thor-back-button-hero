import javax.inject.Inject

plugins {
    id("com.android.application")
    id("jacoco")
}

tasks.named("preBuild") {
    dependsOn(":installGitHooks")
}

// Release signing comes from the environment (CI secrets), never from a committed
// keystore. Locally, these are unset and release builds stay unsigned - that's fine,
// only CI needs to produce an installable release APK. See .github/workflows/release.yml.
val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
val releaseKeystorePassword: String? = System.getenv("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String? = System.getenv("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String? = System.getenv("RELEASE_KEY_PASSWORD")

/**
 * Compiles the root input helper (src/main/cpp/padhelper.c, see docs/HELPER.md) into an arm64
 * executable named like a native library, so it ships inside the APK. Plain NDK clang against libc
 * and the kernel UAPI - no CMake, no third-party code.
 */
abstract class BuildPadHelper : DefaultTask() {
    @get:InputFile abstract val source: RegularFileProperty
    @get:Input abstract val clang: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val execOps: ExecOperations

    @TaskAction
    fun compile() {
        val abiDir = outputDir.get().dir("arm64-v8a").asFile.apply { mkdirs() }
        execOps.exec {
            commandLine(
                clang.get(), "--target=aarch64-linux-android30", "-O2", "-Wall", "-Wextra", "-Werror", "-s",
                "-o", File(abiDir, "libpadhelper.so").path, source.get().asFile.path,
            )
        }
    }
}

val ndkHostTag = System.getProperty("os.name").lowercase().let {
    when {
        it.contains("mac") -> "darwin-x86_64"
        it.contains("win") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
}

val buildPadHelper = tasks.register<BuildPadHelper>("buildPadHelper") {
    source.set(layout.projectDirectory.file("src/main/cpp/padhelper.c"))
    clang.set(androidComponents.sdkComponents.ndkDirectory.map {
        it.file("toolchains/llvm/prebuilt/$ndkHostTag/bin/clang" + if (ndkHostTag.startsWith("windows")) ".exe" else "").asFile.path
    })
    outputDir.set(layout.buildDirectory.dir("generated/padhelper/jniLibs"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildPadHelper, BuildPadHelper::outputDir)
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

android {
    namespace = "dev.steinhorst.backbuttonhero"
    compileSdk = 34
    // Only used to compile the root input helper (src/main/cpp); the app itself has no JNI.
    ndkVersion = "29.0.14206865"

    defaultConfig {
        // The one place to change on a rebrand (Android's actual "package name").
        // app_name in strings.xml is the other.
        applicationId = "dev.steinhorst.backbuttonhero"
        minSdk = 30
        targetSdk = 34
        versionCode = 7
        versionName = "1.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        // The helper is an executable shipped as lib*.so; pservice runs it from nativeLibraryDir,
        // which only exists when native libraries are extracted at install time.
        jniLibs.useLegacyPackaging = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        abortOnError = true
        // OldTargetApi only fires where a platform newer than targetSdk is installed, so it
        // stays invisible on a machine with just android-34 and turns the CI runner red - and
        // with warningsAsErrors it cannot be left as a warning. targetSdk is pinned at 34 on
        // purpose; a baseline entry would be wrong here because the finding does not exist in
        // every environment. Re-enable when deliberately moving targetSdk forward.
        disable += "OldTargetApi"
    }

    if (releaseKeystorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            enableUnitTestCoverage = true
        }
        release {
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }
    }
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    extensions.configure<org.gradle.testing.jacoco.plugins.JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

private val coverageClassFilter = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
)

tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(coverageClassFilter)
        }
    )
    executionData.setFrom(
        layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    )
}

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")
    sourceDirectories.setFrom(files("src/main/java"))
    classDirectories.setFrom(
        fileTree(layout.buildDirectory.dir("intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes")) {
            exclude(coverageClassFilter)
        }
    )
    executionData.setFrom(
        layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
    )

    violationRules {
        // One rule per class. `counter` and `value` are mandatory: without them JaCoCo
        // measures INSTRUCTION coverage, not LINE. Raise these numbers when tests grow -
        // never lower them.
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.BackGestureMachine")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.98".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.Prefs")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.LevelSteps")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.DpadDirection*")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.DoubleTapDetector")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.AccessibilityAppSource")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.61".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.UpdateChecker")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.46".toBigDecimal()
            }
        }
        rule {
            element = "CLASS"
            includes = listOf("dev.steinhorst.backbuttonhero.CardTouch")
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "1.00".toBigDecimal()
            }
        }
        // Classes already at (nearly) full coverage when the suite was extended for the
        // grouped settings, Recents routing and panel work. Exact class names, so nested
        // classes are not folded in. Same rule: raise, never lower.
        mapOf(
            "RecentsSelection" to "1.00", "ServiceToggleGesture" to "1.00",
            "ExceptionSettingsPage" to "0.98", "PhysicalKeyTracker" to "1.00",
            "AppException" to "1.00", "ExceptionControl" to "1.00",
            "TopRecentsNavigation" to "1.00", "TopRecentsState" to "1.00",
            "RecentTaskQuery" to "1.00", "PanelWindows" to "1.00",
            "RecentsBadges" to "1.00", "SettingsState" to "1.00",
            "PadNavigationMode" to "1.00", "RecentsCardStyle" to "0.95",
            "TaskMovePolicy" to "1.00", "BindingSettings" to "1.00",
            "SettingsNavigation" to "1.00", "ShoulderButton" to "1.00",
            "SettingsPage" to "1.00", "SettingsChangesKt" to "1.00",
            "KeyInterceptionGate" to "1.00", "SettingsUiKitKt" to "1.00",
            "SettingsSection" to "1.00", "BottomPanels" to "0.98",
            "GestureScheduler" to "1.00", "SettingsChangePlan" to "1.00",
        ).forEach { (name, minimum) ->
            rule {
                element = "CLASS"
                includes = listOf("dev.steinhorst.backbuttonhero.$name")
                limit {
                    counter = "LINE"
                    value = "COVEREDRATIO"
                    this.minimum = minimum.toBigDecimal()
                }
            }
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.4.0")
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")
}

