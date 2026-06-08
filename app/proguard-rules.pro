# sshj + EdDSA + bouncycastle (heavy reflection)
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class net.i2p.crypto.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn net.i2p.crypto.**
-dontwarn sun.security.x509.X509Key

# androidx.security (Tink) brings these annotations transitively
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
# Tink also references JSR-305 (javax.annotation) compile-time annotations
# — Nullable / GuardedBy etc. They're not on the runtime classpath by
# design (annotations only), so R8's "Missing class" is a false alarm.
# Without these the release minify step (minifyReleaseWithR8) fails hard:
#   Missing class javax.annotation.Nullable
#     (referenced from com.google.crypto.tink.PrimitiveSet$Entry … +79)
# This rule was lost in the 2026-05-29 stash restore — re-added.
-dontwarn javax.annotation.**

# yubikit-android pulls SpotBugs annotations as a compile-time-only
# dependency; they're not on the runtime classpath but R8 still wants
# the symbols to satisfy bytecode references in the FIDO/CCID code.
-dontwarn edu.umd.cs.findbugs.annotations.**
# Keep yubikit's reflection-driven classes (CTAP CBOR codec, FIDO
# webauthn entities, USB / NFC transport) so R8 doesn't strip the
# fields it accesses by name at runtime.
-keep class com.yubico.yubikit.** { *; }
-dontwarn com.yubico.yubikit.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class ai.eight24family.conch.**$$serializer { *; }
-keepclassmembers class ai.eight24family.conch.** {
    *** Companion;
}
-keepclasseswithmembers class ai.eight24family.conch.** {
    kotlinx.serialization.KSerializer serializer(...);
}
