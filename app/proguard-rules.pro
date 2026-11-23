# Keep ML Kit OCR & Translation classes intact in release builds
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# Keep Kotlin Coroutines & metadata
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keepattributes Signature, InnerClasses, EnclosingMethod
