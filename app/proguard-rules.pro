# WebView / JS bridge keep rules.
# The @JavascriptInterface methods below are called by name from JavaScript,
# so they must not be renamed by R8. (AGP adds these automatically, but they
# are listed explicitly here so the app keeps working if minification is
# switched on in app/build.gradle.kts.)
-keepclassmembers class org.perchance.app.MainActivity$WebBridge {
    public *;
}
-keepattributes *Annotation*
