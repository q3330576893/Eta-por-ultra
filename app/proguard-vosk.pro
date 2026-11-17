# Vosk uses JNA Native.register for native symbol lookup. Match the official Android demo rules.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-keep class org.vosk.LibVosk { *; }
-keep class org.vosk.Model { *; }
-keep class org.vosk.Recognizer { *; }
-dontwarn java.awt.**
