# NativeSerialTransport methods are resolved through JNI. Keep the class and
# native method names when R8 optimizes the release APK.
-keep class com.wx.skb.hostapp.NativeSerialTransport { *; }

# Keep public SDK entry points stable for applications that consume the SDK
# directly, including nested result/exception types.
-keep public class com.wx.skb.** { public protected *; }
