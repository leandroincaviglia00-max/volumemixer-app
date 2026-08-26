# kotlinx.serialization: mantiene i serializer generati
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.remotevolumemixer.**$$serializer { *; }
-keepclassmembers class com.remotevolumemixer.** {
    *** Companion;
}
-keepclasseswithmembers class com.remotevolumemixer.** {
    kotlinx.serialization.KSerializer serializer(...);
}
