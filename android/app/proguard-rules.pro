-keep class app.independentwallet.bindings.** { *; }
-keep class com.sun.jna.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# JNA contains optional desktop AWT helpers; Android has no java.awt classes.
# These are not part of our UniFFI wallet bridge on Android.
-dontwarn java.awt.**
