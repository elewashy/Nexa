# ================================================================
# ProGuard / R8 rules for Nexa — Production Release
# ================================================================

# ================================
# General Android Rules
# ================================

# Keep line numbers and source file names for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep all annotations (needed by Gson and Compose)
-keepattributes *Annotation*

# Keep generic signatures (needed by Gson TypeToken)
-keepattributes Signature

# Keep inner classes and enclosing methods
-keepattributes InnerClasses,EnclosingMethod

# Keep exception information
-keepattributes Exceptions

# ================================
# WebView with JavaScript
# ================================

# Keep WebView JavaScript interfaces
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ================================
# Gson (JSON Serialization)
# ================================

-dontwarn sun.misc.**

# Keep Gson TypeToken for generic deserialization
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# Keep @SerializedName-annotated fields from being renamed
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Keep all model classes used with Gson (field names must be preserved)
-keep class com.elewashy.nexa.feature.downloads.domain.model.** { *; }
-keep class com.elewashy.nexa.feature.splash.domain.model.** { *; }
-keep class com.elewashy.nexa.feature.settings.filterupdates.domain.model.** { *; }
-keep class com.elewashy.nexa.feature.share.domain.model.** { *; }

# ================================
# OkHttp 5.x
# ================================

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# OkHttp uses reflection for platform-specific TLS; keep platform adapters
-keep class okhttp3.internal.platform.** { *; }
-dontwarn okhttp3.internal.platform.**

# Okio (OkHttp dependency)
-dontwarn okio.**

# Animal Sniffer
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ================================
# Kotlin Coroutines
# ================================

-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Keep Kotlin metadata
-keep class kotlin.Metadata { *; }

# ================================
# AndroidX and Material Components
# ================================

-dontwarn androidx.**
-dontwarn com.google.android.material.**

# ================================
# Jetpack Compose
# ================================

-dontwarn androidx.compose.**

# ================================
# App-Specific Rules
# ================================

# Keep all activities, services, and receivers (referenced in Manifest)
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# Keep all enum classes and their members (valueOf/values used by serialization)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ================================
# Specific Persistence Objects
# ================================

# Stored as JSON via Gson — field names must survive obfuscation
-keep class com.elewashy.nexa.feature.downloads.data.engine.DownloadSegment { *; }

# ================================
# Hilt / Dagger (Dependency Injection)
# ================================

# Hilt generates entry points, components, and generated classes at compile time.
# R8's call-graph keeps most of them, but a few reflection-accessed types need help.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager
-keep class * extends dagger.hilt.android.internal.lifecycle.HiltViewModelFactory
-keepclassmembers class * {
    @dagger.hilt.android.lifecycle.HiltViewModel <init>(...);
}
-keep @dagger.hilt.android.HiltAndroidApp class *
-keep @dagger.hilt.android.AndroidEntryPoint class *
-dontwarn dagger.hilt.**
-dontwarn javax.inject.**

# Hilt uses generated Hilt_* wrapper classes for @AndroidEntryPoint
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_GeneratedInjector { *; }
-keep class **_Impl { *; }
-keep class hilt_aggregated_deps.** { *; }

# ================================
# DataStore
# ================================

-dontwarn androidx.datastore.**
-keep class androidx.datastore.** { *; }

# ================================
# Optimization Settings
# ================================

-optimizationpasses 7
-dontskipnonpubliclibraryclasses
-allowaccessmodification
-repackageclasses ''
-mergeinterfacesaggressively
-overloadaggressively

# ================================
# Strip Logging in Release Builds
# ================================

-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

# ================================
# Security: Obfuscation Hardening
# ================================

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
}

# ================================
# Native Methods
# ================================

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Suppress warnings that don't affect runtime
-dontwarn java.lang.invoke.StringConcatFactory
