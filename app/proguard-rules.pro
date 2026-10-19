# --- R8 / ProGuard ---------------------------------------------------------
# Minification is currently OFF for release (see app/build.gradle.kts). These rules
# are already in place so that turning isMinifyEnabled = true is a one-line change.

# --- kotlinx.serialization -------------------------------------------------
# The plugin generates a `Companion.serializer()` for every @Serializable class,
# but it looks those up reflectively via `.Companion` / `serializer()`.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, AnnotationDefault

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.Serializable <methods>;
}

# --- Retrofit --------------------------------------------------------------
# Generic signatures on suspend functions tell Retrofit the response type.
-keepattributes Signature, Exceptions
-if interface * { @retrofit2.http.* <methods>; }
-keep,allowobfuscation interface <1>
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# --- OkHttp ----------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# --- Hilt / Dagger ---------------------------------------------------------
-dontwarn dagger.hilt.**

# --- Google ML Kit text recognition ---------------------------------------
# The bundled Latin OCR model is loaded reflectively.
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
