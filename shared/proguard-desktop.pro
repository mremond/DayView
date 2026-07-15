# Keep rules for the minified Compose Desktop (macOS/Linux) release build.

# Desktop entry point launched reflectively by the Compose/JVM launcher.
-keep class fr.dayview.app.MainKt { *; }

# JNA binds native code via reflection over these types.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }

# Our JNA Library interfaces (the Objective-C runtime bridges) are proxied by JNA.
-keep interface * extends com.sun.jna.Library { *; }

# Compose-generated resource accessors are reached by name.
-keep class fr.dayview.app.generated.resources.** { *; }

# Optional/desktop-only references R8 can't see through.
-dontwarn org.jetbrains.**
-dontwarn com.sun.jna.**
