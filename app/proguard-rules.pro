# kotlinx.serialization and OkHttp ship their own consumer rules.
# Keep serializable data models used with reflection-free serializers.
-keep,includedescriptorclasses class dev.mriya.data.**$$serializer { *; }
-keepclassmembers class dev.mriya.data.** { *** Companion; }
-keep,includedescriptorclasses class dev.mriya.domain.**$$serializer { *; }
-keepclassmembers class dev.mriya.domain.** { *** Companion; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Tesseract4Android / JNI native methods
-keep class com.googlecode.tesseract.android.** { *; }
-dontwarn com.googlecode.tesseract.android.**
-keepclasseswithmembernames class * {
    native <methods>;
}
