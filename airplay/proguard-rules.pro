# The native AirPlay core calls these by name through JNI.
-keep interface com.amusic.airplay.NativeCallbacks { *; }
-keepclassmembers class * implements com.amusic.airplay.NativeCallbacks { *; }
-keep class com.amusic.airplay.AirPlayNative { *; }
