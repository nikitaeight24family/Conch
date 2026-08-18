import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.Project
import com.github.triplet.gradle.androidpublisher.ReleaseStatus

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
    alias(libs.plugins.play.publisher)
}

android {
    namespace = "ai.eight24family.conch"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.eight24family.conch"
        minSdk = 26
        // Google Play target-API policy: updates submitted on/after
        // 2026-08-31 must target Android 16 (API 36). Bumped 35 → 36
        // (up, never down — see CLAUDE.md §8). No manifest/runtime changes:
        // app is already adaptive (resizeableActivity, no orientation lock),
        // edge-to-edge + dataSync timeout + 16 KB pages all already applied
        // at target 35. compileSdk stays 37.
        targetSdk = 36
        // versionCode is sourced from `gitCommitCount()` so every push
        // produces a strictly-monotonic value Play Console will accept.
        // Manual bumps still work — `versionCodeOverride` (set via
        // `-PversionCodeOverride=N` or `VERSION_CODE` env var) takes
        // precedence for hotfixes / re-uploads of an existing tag.
        versionCode = computeVersionCode(project)
        // Live on Google Play. versionCode stays auto (git commit count) —
        // strictly monotonic, never reset, independent of this label.
        //
        // versionName rule: `baseVersionName` is the CLEAN name that ships to
        // GitHub/Play — set to the NEXT version being worked toward. It reaches
        // the store ONLY via a full release build (`assembleRelease` /
        // `bundleRelease`, NO -PfastRelease). LOCAL on-device dev builds always
        // use `-PfastRelease`, and those get a `-nightly` suffix so the phone
        // NEVER claims the same version string as the published store build.
        // Per-build the nightly is still distinguishable by its git-derived
        // versionCode, shown in About as "build N".
        val baseVersionName = "0.3.6"
        versionName = baseVersionName + if (project.hasProperty("fastRelease")) "-nightly" else ""

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
            // Verbose diagnostic logging (SshAi-* Logx.d/w) — always ON for
            // debug builds.
            buildConfigField("boolean", "VERBOSE_LOGS", "true")
        }
        release {
            // Verbose diagnostic logging is OFF in release by default — the
            // Play Store / GitHub artifact ships QUIET. Flip it on for a
            // dev-iteration build with `-PverboseLogs` when you need the
            // SshAi-* traces (e.g. `assembleRelease -PfastRelease
            // -PverboseLogs`). CI / `bundleRelease` never pass it → quiet.
            // R8 sees the `false` const and strips the gated calls + their
            // string-building entirely. Routed through [util.Logx]. NOT a
            // user-facing setting — purely compile-time (user, 2026-06-13).
            buildConfigField(
                "boolean", "VERBOSE_LOGS",
                project.hasProperty("verboseLogs").toString(),
            )
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
            // Bundle native debug symbols into the AAB's BUNDLE-METADATA so
            // Play can symbolicate native crash / ANR stacks.
            //
            // ⚠ ON THIS MACHINE IT IS A NO-OP, and that is deliberate. The
            // extraction task shells out to the NDK's objcopy; there is no NDK
            // under C:\Android\Sdk, so AGP skips it silently and the AAB ships
            // without a `com.android.tools.build.debugsymbols` entry — which is
            // exactly the "upload a symbol file" warning Play shows on every
            // upload. Setting it here costs nothing and would start producing
            // symbols the moment a build machine with an NDK builds a bundle —
            // note that today NO such machine exists: the Play artifact is only
            // ever built here, and neither .github workflow runs `bundleRelease`.
            //
            // Installing an NDK just to clear that warning would buy close to
            // nothing, measured rather than assumed: every .so we ship is a
            // stripped third-party prebuilt. In the 0.2.11 bundle the only
            // symbol data present anywhere was `.dynsym` (exports, which Play
            // resolves from the library itself) plus a 1296-byte `.symtab` in
            // libdatastore_shared_counter.so. There is no `.debug_info` and no
            // `.gnu_debuglink` to extract — re-measure with
            // `python tools/elfsections.py <aab>` before revisiting this.
            ndk {
                debugSymbolLevel = "FULL"
            }
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
    // Play SDK Index flagged `androidx.fragment:fragment:1.1.0` as outdated on the
    // live release (2026-07-30). It is nobody's declared dependency here — the
    // edge is `camera:camera-view -> appcompat:1.1.0 -> fragment:1.1.0`, and it
    // arrived with the viewfinder in 0.2.11, NOT with the 1.3.4 -> 1.4.2 bump:
    // camera-view 1.3.4's POM asks for the same appcompat 1.1.0.
    //
    // This app is pure Compose and touches no AppCompat or Fragment API (nothing
    // extends AppCompatActivity or FragmentActivity), so the safe fix is to raise
    // the pair rather than exclude it: excluding a declared transitive dependency
    // risks a NoClassDefFoundError from camera-view internals, whereas raising it
    // keeps an AndroidX-tested appcompat/fragment combination on the classpath.
    // Constraints rather than `implementation` — we want the version floor
    // without adding an API surface for our own code to start using by accident.
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("Play SDK Index: 1.1.0 is outdated; 1.2.1+ required")
        }
        implementation("androidx.appcompat:appcompat:1.7.1") {
            because("keeps appcompat paired with a fragment version AndroidX tests it against")
        }
    }

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

    // CameraX — live viewfinder in the attachment sheet (Telegram-style first cell).
    implementation(libs.androidx.photopicker.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

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

// ── Google Play publishing (gradle-play-publisher) ──────────────────────────
// Uploads the signed AAB + "What's new" to Play via the Developer API, so an
// update is one command (`./gradlew publishReleaseBundle`) instead of a manual
// Console upload.
//
// SECRETS: the service-account JSON key is NEVER committed. Its PATH is read
// from a gradle property `playServiceAccountFile` (set locally in
// GRADLE_USER_HOME/gradle.properties — bang-free, out of git) or the
// PLAY_SERVICE_ACCOUNT_JSON env var. If neither is set, the credentials aren't
// wired and the publish tasks fail loudly rather than doing anything surprising.
play {
    val credPath = providers.gradleProperty("playServiceAccountFile").orNull
        ?: System.getenv("PLAY_SERVICE_ACCOUNT_JSON")
    if (credPath != null) serviceAccountCredentials.set(file(credPath))
    // Default to the INTERNAL testing track so production users never get an
    // accidental push. Target another track per-run, e.g.:
    //   ./gradlew publishReleaseBundle -Pplaytrack=production
    track.set(providers.gradleProperty("playtrack").orElse("internal"))
    // Full release (no staged fraction). For a staged production rollout use
    // -Pplaytrack=production with a userFraction override later.
    releaseStatus.set(ReleaseStatus.COMPLETED)
    // We hand GPP the AAB, not APKs.
    defaultToAppBundles.set(true)
}

// ── 16 KB page-size gate ────────────────────────────────────────────────────
// Google Play rejects a bundle whose 64-bit native libraries are not
// 16 KB-page-safe, and on a 16 KB-page device such a library does not load at
// all — the feature behind it simply breaks. 0.2.11 shipped one (camera-core
// 1.3.4's libimage_processing_util_jni.so) and Play caught it, not us.
//
// The check reads the ELF program headers straight out of the AAB: every
// PT_LOAD segment of every arm64-v8a / x86_64 `.so` must declare
// p_align >= 16384. It is deliberately dependency-free (java.util.zip +
// ByteBuffer) so it runs identically here and on CI, with no NDK and no Python.
//
// NOTE the failure mode this guards against: we ship no native code of our own,
// so the offender is always a transitive prebuilt, and the only fix is the
// dependency version. Neither zipalign nor any packaging flag can re-align a
// prebuilt .so — see INVARIANTS.md (2026-07-30).
abstract class VerifyNativeAlignment : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile
    abstract val bundle: RegularFileProperty

    @get:org.gradle.api.tasks.OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val required = 16384L
        val lines = mutableListOf<String>()
        val bad = mutableListOf<String>()
        // A guard must not be able to pass by inspecting nothing, so both "no
        // 64-bit libraries at all" and "a 64-bit library I could not read" are
        // failures rather than skips. Neither is reachable today; that is exactly
        // when to nail the contract down, while the expected numbers are known.
        var native32 = 0
        // NOTE the imports at the top of this script: inside a Gradle Kotlin DSL
        // build file the identifier `java` is the JavaPluginExtension, so a
        // fully-qualified `java.util.zip.ZipFile` does NOT compile here.
        ZipFile(bundle.get().asFile).use { zip ->
            zip.entries().asSequence()
                .filter { e -> e.name.endsWith(".so") }
                .sortedBy { it.name }
                .forEach { entry ->
                    val abis = entry.name.split('/')
                    if (!abis.any { it == "arm64-v8a" || it == "x86_64" }) {
                        // 16 KB pages are a 64-bit concern only; count the 32-bit
                        // libraries so we can tell "no native code" apart from
                        // "native code whose 64-bit slices went missing".
                        if (abis.any { it == "armeabi-v7a" || it == "x86" }) native32++
                        return@forEach
                    }
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val worst = minLoadAlign(bytes)
                    if (worst == null) {
                        bad += "${entry.name} (not a readable 64-bit ELF)"
                        lines += "BAD  unreadable  ${entry.name}"
                        return@forEach
                    }
                    lines += "${if (worst >= required) "OK " else "BAD"}  $worst  ${entry.name}"
                    if (worst < required) bad += "${entry.name} (p_align=$worst)"
                }
        }
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        lines.forEach { logger.lifecycle("  $it") }
        if (lines.isEmpty() && native32 > 0) {
            throw GradleException(
                "16 KB page-size gate FAILED -- the archive ships $native32 32-bit " +
                    "native library(ies) and NOT ONE 64-bit library. Play requires a " +
                    "64-bit slice for every native ABI, so this artifact would be " +
                    "rejected on that ground alone, and this gate would otherwise have " +
                    "reported success after checking nothing."
            )
        }
        if (bad.isNotEmpty()) {
            throw GradleException(
                "16 KB page-size gate FAILED -- these 64-bit libraries are aligned " +
                    "below $required and Play will reject the bundle:\n" +
                    bad.joinToString("\n") { "  - $it" } +
                    "\n\nFix the DEPENDENCY that ships the library (bump to a build " +
                    "made with -Wl,-z,max-page-size=16384). Repacking or zipaligning " +
                    "cannot change a prebuilt .so's segment alignment."
            )
        }
        logger.lifecycle("16 KB page-size gate: ${lines.size} 64-bit libraries, all aligned.")
    }

    /** Smallest `p_align` across the PT_LOAD segments of a 64-bit LE ELF. */
    private fun minLoadAlign(data: ByteArray): Long? {
        if (data.size < 64) return null
        if (!(data[0] == 0x7F.toByte() && data[1] == 'E'.code.toByte() &&
                data[2] == 'L'.code.toByte() && data[3] == 'F'.code.toByte())
        ) return null
        if (data[4] != 2.toByte()) return null          // ELFCLASS64 only
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = bb.getLong(0x20)
        val phentsize = bb.getShort(0x36).toInt() and 0xFFFF
        val phnum = bb.getShort(0x38).toInt() and 0xFFFF
        var worst = Long.MAX_VALUE
        for (i in 0 until phnum) {
            val off = (phoff + i.toLong() * phentsize).toInt()
            if (off < 0 || off + 0x38 > data.size) break
            if (bb.getInt(off) != 1) continue           // PT_LOAD
            worst = minOf(worst, bb.getLong(off + 0x30))
        }
        return worst.takeIf { it != Long.MAX_VALUE }
    }
}

val verifyReleaseNativeAlignment =
    tasks.register<VerifyNativeAlignment>("verifyReleaseNativeAlignment") {
        group = "verification"
        description = "Fails if any 64-bit .so in the release AAB is not 16 KB-page aligned."
        bundle.set(layout.buildDirectory.file("outputs/bundle/release/app-release.aab"))
        report.set(layout.buildDirectory.file("reports/native-align/release.txt"))
        dependsOn("bundleRelease")
    }

// A plain `bundleRelease` is verified too — that is the artifact a human uploads
// by hand — and no publish task may run without the gate having passed.
tasks.matching { it.name == "bundleRelease" }.configureEach {
    finalizedBy(verifyReleaseNativeAlignment)
}
tasks.matching { it.name == "publishReleaseBundle" || it.name == "publishBundle" }
    .configureEach { dependsOn(verifyReleaseNativeAlignment) }
