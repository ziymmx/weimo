# Weimo: release builds do not enable R8/obfuscation.
# Keep entries below are kept for safety in case minification is enabled later.
-dontwarn java.lang.reflect.AnnotatedType
-keep class com.ziymmx.wx.** { *; }
-dontobfuscate