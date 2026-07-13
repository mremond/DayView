# R8 rules for the DayView release build.
# Compose, AndroidX and kotlinx libraries ship their own consumer rules,
# so nothing app-specific is required yet. Add rules here if a release
# build starts stripping reflection-accessed classes.

# androidx.security:security-crypto (EncryptedSharedPreferences) bundles Google
# Tink, which references errorprone annotations that aren't on the runtime
# classpath. They are compile-only annotations, so it is safe to silence R8's
# missing-class warnings for them.
-dontwarn com.google.errorprone.annotations.**
