plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9.x provides built-in Kotlin — KSP 2.3.x supports it, so we
    // don't apply the standalone kotlin-android plugin anymore. KGP is
    // pulled in by AGP.
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
