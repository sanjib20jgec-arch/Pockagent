# PocketAgent release rules
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn org.bouncycastle.**
-dontwarn java.awt.**
-dontwarn javax.imageio.**
-keepclasseswithmembernames class * { native <methods>; }
