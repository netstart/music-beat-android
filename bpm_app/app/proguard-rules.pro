# AndroidX / Material
-dontwarn com.google.android.material.**
-keep class com.google.android.material.** { *; }

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Media3 ExoPlayer (reflection used by some components)
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep ViewBinding generated classes
-keep class **.databinding.* { *; }
-keep class com.example.bpm_player.databinding.* { *; }

# Preserve line numbers for crash reports (reasonable trade-off)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
