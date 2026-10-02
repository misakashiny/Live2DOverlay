# WebView JS 桥接方法通过反射调用，必须保留
-keepclassmembers class com.live2d.overlay.Live2DJSBridge {
    public *;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# 不混淆 JS 接口类名（@JavascriptInterface 注解的方法名会暴露给 JS）
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
