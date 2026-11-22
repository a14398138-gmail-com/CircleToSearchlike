# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Circle OCR / PLaMo keep rules (R8) ---
# JNI bridge: native methods and the streaming callback are looked up by name.
-keep class com.koike.llama.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# App entry points referenced from the manifest / voice-interaction XML.
-keep class com.example.MainActivity { *; }
-keep class com.example.assist.** { *; }
-keep class com.example.share.ChosenComponentReceiver { *; }
# ML Kit ships its own consumer rules; keep model/entry classes defensively.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**
