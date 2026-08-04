# UniFFI generated bindings + JNA: reflection-driven, must survive R8.
# Verified-on-hardware before any tester build is a standing phase gate
# (planning doc §8 — the provider parcelable incident is the reason).
-keep class uniffi.vault_ffi.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-dontwarn java.awt.*

# DocumentsProvider is instantiated by the system.
-keep class dev.norsehorse.vaultpony.provider.** { *; }
