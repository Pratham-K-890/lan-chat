# ── Jackson ──────────────────────────────────────────────────────────────────
# Jackson uses reflection to serialize/deserialize — without these rules,
# R8 will strip the fields and minification will break JSON parsing.
-keep class com.fasterxml.jackson.** { *; }
-keepnames class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# ── LAN Chat common library ───────────────────────────────────────────────────
# Keep all model/protocol/crypto/util classes used from common.jar
-keep class com.lanchat.common.** { *; }
-keepclassmembers class com.lanchat.common.** { *; }

# ── LAN Chat client classes ───────────────────────────────────────────────────
-keep class com.lanchat.client.** { *; }
-keepclassmembers class com.lanchat.client.** { *; }

# ── jBCrypt ───────────────────────────────────────────────────────────────────
-keep class org.mindrot.** { *; }

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── Kotlin serialization metadata (for reflection) ────────────────────────────
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# ── General Android safe rules ────────────────────────────────────────────────
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
