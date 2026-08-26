# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.remotemixer.app.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.remotemixer.app.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}
