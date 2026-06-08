import java.io.ByteArrayOutputStream
import java.util.Properties
import org.gradle.api.Project

/**
 * Compute a strictly-monotonic [versionCode] for this build.
 *
 * Order of preference:
 *   1. `-PversionCodeOverride=N` (gradle property) — hotfix lever; takes
 *      precedence over everything so we can re-upload bumped builds for
 *      a previously-failed Play track without a phantom commit.
 *   2. `VERSION_CODE` env var — same idea but from CI.
 *   3. `git rev-list --count HEAD` — every commit is one notch up. Falls
 *      back to a hand-picked floor if git isn't available (shallow
 *      clones, sources zip, etc.) so we never produce a 0 by accident.
 *
 * Play Console accepts up to 2_100_000_000, so a commit-count code is
 * fine for ~5,800 years of daily commits — not a real concern.
 */
fun computeVersionCode(project: Project): Int {
    val override = (project.findProperty("versionCodeOverride") as String?)?.toIntOrNull()
        ?: System.getenv("VERSION_CODE")?.toIntOrNull()
    if (override != null) return override

    // `providers.exec` (vs. `project.exec`) is the configuration-cache-
    // friendly form: Gradle records the command + outputs and reuses
    // them on subsequent builds without re-running git. Plain
    // `project.exec` at configuration time fails with
    // "Starting an external process during configuration time is
    // unsupported" once the configuration cache is on (which it is for
    // this project — see gradle.properties).
    return try {
        val provider = project.providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            isIgnoreExitValue = true
        }
        val rc = provider.result.get()
        if (rc.exitValue == 0) {
            val text = provider.standardOutput.asText.get().trim()
            text.toIntOrNull()?.takeIf { it > 0 } ?: MIN_VERSION_CODE
        } else MIN_VERSION_CODE
    } catch (_: Throwable) {
        MIN_VERSION_CODE
    }
}

/**
 * Floor for [computeVersionCode] when git is unavailable. Set above the
 * highest manually-published Play Console code so a fallback build can
 * never collide with one we've already shipped.
 */
private val MIN_VERSION_CODE = 100

plugins {
    alias(libs.plugins.android.application)
    // kotlin-android removed — AGP 9.x ships built-in Kotlin and KSP
    // 2.3.x is built-in compatible.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.sentry.android)
}

android {
    namespace = "ai.eight24family.conch"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.eight24family.conch"
        minSdk = 26
        targetSdk = 35
        // versionCode is sourced from `gitCommitCount()` so every push
        // produces a strictly-monotonic value Play Console will accept.
        // Manual bumps still work — `versionCodeOverride` (set via
        // `-PversionCodeOverride=N` or `VERSION_CODE` env var) takes
        // precedence for hotfixes / re-uploads of an existing tag.
        versionCode = computeVersionCode(project)
        // Public launch as an early beta. versionCode stays auto (git commit
        // count) — strictly monotonic, never reset, independent of this label.
        versionName = "0.1.0 Beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Sentry DSN baked in at build time — empty for local debug builds
        // (no crash reporting), real value injected by CI from a secret on
        // tagged release builds. SshAiApp.onCreate() skips Sentry init
        // entirely when the field is blank.
        val dsn = (project.findProperty("sentryDsn") as String?) ?: System.getenv("SENTRY_DSN") ?: ""
        buildConfigField("String", "SENTRY_DSN", "\"$dsn\"")
    }

    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            // Use a separate package id for debug builds so they install
            // alongside the release build instead of conflicting with it.
            // Without this, a local `assembleDebug` would overwrite the
            // user's release install — and worse, signature mismatch
            // forces `adb uninstall`, wiping all app data (servers, keys,
            // chat history). The `.debug` suffix gives debug builds their
            // own package row so the two coexist.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Dev-iteration escape hatch: `-PfastRelease` skips R8/minify +
            // resource shrinking so a release-SIGNED APK builds in ~1 min
            // instead of ~10. Same signing key → installs over a prior
            // release/Play build with NO data wipe (debug builds can't:
            // different keystore + `.debug` suffix). R8 is pure overhead for
            // "build → adb install on my own phone → test" — it only earns
            // its keep for the Play Store artifact (smaller download +
            // obfuscation) and the one-time missing-keep-rule check.
            //
            // ⚠ NEVER pass -PfastRelease for the Play Store `bundleRelease`:
            // the shipped AAB MUST be minified. CI does not set it; the
            // default (no flag) keeps full minify on, so a plain
            // `./gradlew bundleRelease` is always Play-correct.
            val fastRelease = project.hasProperty("fastRelease")
            isMinifyEnabled = !fastRelease
            isShrinkResources = !fastRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // AGP 9.x removed android.kotlinOptions { ... } — moved to the
    // top-level `kotlin { compilerOptions { ... } }` block below.
    buildFeatures {
        compose = true
        // Required for `BuildConfig.SENTRY_DSN` we add via buildConfigField.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE",
                "/META-INF/LICENSE.txt",
                "/META-INF/NOTICE",
                "/META-INF/NOTICE.txt",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }

    // Robolectric needs real Android resources (themes, strings, drawables)
    // when rendering Composables in JVM unit tests; without this flag the
    // resource lookup at test time returns null and the Compose tree fails
    // to inflate. Bumps the unit-test classpath but keeps tests pure-JVM.
    //
    // `isReturnDefaultValues` is INTENTIONALLY off — when on, it stubs
    // every Android framework call to return null/0/false BEFORE
    // Robolectric's sandbox classloader gets a chance to load the
    // instrumented Configuration class, which then trips the
    // `noncompatWidthPixels` NoSuchFieldError that Robolectric uses
    // reflectively in DeviceConfig. The official guidance is: pick one
    // of these flags, never both.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // SilentlyTry.recordSwallow calls android.util.Log.w on every
        // swallow, which fails in plain JVM unit tests with "Method w
        // in android.util.Log not mocked". Returning default values
        // (Log.w → 0) is harmless for tests and the right opt-in
        // story once we have observability-aware swallows everywhere.
        unitTests.isReturnDefaultValues = true
    }
}

// AGP 9.x replacement for android.kotlinOptions { ... }. Sets the
// Kotlin compile target JVM bytecode level. The KGP DSL is now
// top-level (kotlin { ... }) rather than nested under the android
// extension.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Sentry Gradle plugin config: only really matters for release builds in CI,
// where we have an auth token AND want R8 mapping uploaded so stacktraces
// are deobfuscated in the dashboard. For local debug builds we keep
// everything off so the build doesn't try to phone home to Sentry.
sentry {
    val sentryToken = System.getenv("SENTRY_AUTH_TOKEN")?.takeIf { it.isNotBlank() }
    val sentryOrg = System.getenv("SENTRY_ORG")?.takeIf { it.isNotBlank() }
    val sentryProj = System.getenv("SENTRY_PROJECT")?.takeIf { it.isNotBlank() }
    // Only attempt the mapping upload when ALL three Sentry secrets are set.
    // A misconfigured CI (e.g. token present but project slug wrong) 404'd
    // the v1.0.9 release and skipped the GitHub Release publish entirely;
    // the mapping upload is a nice-to-have, not a release blocker. With this
    // gate, a partial Sentry config silently disables the upload and the
    // signed APK still ships.
    val mappingUploadOk = sentryToken != null && sentryOrg != null && sentryProj != null
    autoUploadProguardMapping.set(mappingUploadOk)
    includeProguardMapping.set(mappingUploadOk)
    org.set(sentryOrg ?: "")
    projectName.set(sentryProj ?: "sshai")
    authToken.set(sentryToken ?: "")
    // Our Sentry organization is hosted in the DE (Frankfurt) region.
    // Without an explicit url, sentry-cli 2.39.1 hits the legacy
    // `/api/0/organizations/<slug>/region/` (singular) endpoint on
    // sentry.io for region discovery, which 404s for SaaS users and
    // causes the mapping upload to fail with "Project does not exist".
    // Pinning the URL skips that lookup entirely. SENTRY_URL env var
    // (if set) still overrides this — useful if the org ever moves.
    url.set(System.getenv("SENTRY_URL")?.takeIf { it.isNotBlank() } ?: "https://de.sentry.io")
    // We add the SDK dependency manually below; turn off the plugin's
    // auto-installation to avoid duplicate-dependency warnings.
    autoInstallation { enabled.set(false) }
    // Tracing instrumentation costs build time + APK size for features we
    // don't surface yet (separate Phase 1.4 issue). Keep it off until
    // performance traces are explicitly enabled.
    tracingInstrumentation { enabled.set(false) }
    // Don't upload native debug symbols — we have no native code.
    uploadNativeSymbols.set(false)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.haze)
    // Override compose-foundation to 1.12.0-alpha03+ so we get
    // ComposeFoundationFlags.isSelectionAutoScrollEnabled (default true)
    // — Compose ships the SelectionContainer-handle-drag auto-scroll
    // feature only from this version onwards (BOM 2025.06.01 pins
    // foundation 1.8.x which doesn't have the field, so reflection-based
    // toggle no-ops). Explicit dependency wins over the BOM constraint.
    implementation("androidx.compose.foundation:foundation:1.12.0-alpha03")

    // Foldable / tablet adaptive UI. Phase 0 pulls these in so any
    // composable can read `currentWindowAdaptiveInfo()` and the
    // ViewModel-level fold flow lives in `androidx.window`. Phase 1+
    // adds the `ListDetail` / `SupportingPane` scaffolds on top.
    implementation(libs.androidx.window)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.androidx.material3.adaptive.navigation.suite)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.core.splashscreen)
    // Chrome Custom Tabs — used by [CustomTabUriHandler] to open
    // any URL the agent mentions in an in-app browser surface that
    // inherits the user's default browser session (cookies / extensions
    // / saved passwords). Lighter than embedding a WebView and avoids
    // the "kicked out of the app" UX of a plain ACTION_VIEW.
    implementation("androidx.browser:browser:1.8.0")

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    // PersistentList / PersistentMap with structural sharing. Powers
    // AgentSession history vector + id-to-index map so non-streaming
    // emits and id lookups stop being O(N) on 1000-message sessions.
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.3.8")
    // coil removed 2026-05-29 — Durov dep-audit found ZERO usage across the
    // codebase (no `import coil`, no AsyncImage). Dead weight in the APK.
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    // DocumentFile — used by the Settings "Downloads folder" picker
    // to resolve a human-readable label from a SAF tree URI.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Code syntax highlighting for the built-in text viewer.
    // Pure Kotlin Multiplatform, no WebView. Returns token spans
    // (start/end + type) which we map to Compose SpanStyles when
    // rendering. Supports Kotlin / Java / Rust / Go / JS / TS / Python
    // / Ruby / Shell / Swift / PHP / C / C++ / Dart / Perl / C# /
    // CoffeeScript — JSON and YAML/TOML fall back to a minimal
    // tokenizer in `util/CodeHighlighter.kt` (strings/numbers/
    // comments only) because the lib doesn't ship grammars for them.
    // https://github.com/SnipMeDev/Highlights
    implementation("dev.snipme:highlights:1.1.0")

    implementation(libs.sshj)
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)

    // Hardware security key support (FIDO2/CTAP2 over USB + NFC).
    // Used to register `sk-ssh-ed25519` / `sk-ecdsa-sha2-nistp256` keys
    // and to drive the per-connection getAssertion when authenticating
    // against an SSH server. See ssh/securitykey/ for the integration.
    implementation(libs.yubikit.android)
    implementation(libs.yubikit.fido)

    // Shizuku — optional. Adds the service binder + ContentProvider
    // that lets users grant ssh.ai shell-level permissions (READ_LOGS,
    // dumpsys, pm queries) without rooting the device. We probe at
    // runtime whether the manager app is installed; absence is fine.
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.sentry.android)

    debugImplementation(libs.androidx.ui.tooling)
    // Compose UI test artifact ships an empty AndroidManifest.xml that needs
    // to be merged into the app manifest at debug time so ComposeTestRule
    // can host Composables under Robolectric. debugImplementation is the
    // canonical scope per Compose docs.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.apache.mina.sshd.core)
    // Pure-JVM SQLite — used by migration tests so they don't need to spin
    // up the full Robolectric Android sandbox just to run a few SQL DDL
    // statements. Faster (~50ms/test vs ~3s) and side-steps the
    // Robolectric+JDK17 reflection issues with Configuration internals.
    testImplementation(libs.xerial.sqlite.jdbc)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
