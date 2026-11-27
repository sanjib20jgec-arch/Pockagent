# PocketAgent release rules

# Aggressive obfuscation & optimization
-repackageclasses 'o'
-allowaccessmodification
-optimizationpasses 5
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# Strip debug/verbose/info logs in release
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# JNI / native entrypoints & LiteRT-LM runtime
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.google.ai.edge.litertlm.** { *; }

# Surgical PDFBox rules (allows R8 Full Mode to strip unused rendering/font/crypto classes)
-keep class com.tom_roush.pdfbox.android.PDFBoxResourceLoader { *; }
-keep class com.tom_roush.pdfbox.pdmodel.PDDocument { *; }
-keep class com.tom_roush.pdfbox.text.PDFTextStripper { *; }
-keep class com.tom_roush.pdfbox.io.MemoryUsageSetting { *; }

-dontwarn kotlin.reflect.**
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
