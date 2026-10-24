# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.myai.assistant.** {
    *** Companion;
}
-keepclasseswithmembers class com.myai.assistant.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.myai.assistant.core.model.**$$serializer { *; }
-keepclassmembers class com.myai.assistant.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.myai.assistant.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
