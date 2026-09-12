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

# Start.io (formerly StartApp) SDK rules.
# The broad keep rule is often required by the SDK to function correctly due to reflection.
-keep class com.startapp.** {
    *;
}

-keep class com.truenet.** {
    *;
}

-keepattributes Exceptions, InnerClasses, Signature, Deprecated, SourceFile, LineNumberTable, *Annotation*, EnclosingMethod
-dontwarn android.webkit.JavascriptInterface
-dontwarn com.startapp.**

-dontwarn org.jetbrains.annotations.**

# Keep LSPosed entrypoints and config names stable in release builds.
-keep class com.catsmoker.app.features.spoofdevice.root.LSPosedModule { *; }
-keep class com.catsmoker.app.shared.data.model.LSPosedConfig { *; }

-dontwarn de.robv.android.xposed.**

# ShellRunner reflects Shizuku's private newProcess(String[], String[], String) — the one-shot
# remote-shell channel (BattleGrounds_GFX's mechanism) that works when the user-service helper
# will not start. R8 must not rename/remove the method on the library class.
-keepclassmembers class rikka.shizuku.Shizuku {
    private static java.lang.Process newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}
