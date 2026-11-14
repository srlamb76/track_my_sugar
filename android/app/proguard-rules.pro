# The WebView calls these methods by name from JavaScript (TmsNative.request /
# saveFile), so R8 must not rename or remove them.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
