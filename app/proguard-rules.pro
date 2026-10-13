# SPASHA proguard rules (release builds are not minified).
# Keep TensorFlow Lite Task Library classes if minification is enabled later.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.mediapipe.** { *; }
