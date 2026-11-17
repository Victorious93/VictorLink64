# Moonlight's JNI bridge calls back into these Java classes by name.
-keep class com.limelight.nvstream.jni.** { *; }
-keep class com.limelight.binding.video.MediaCodecDecoderRenderer { *; }
-dontwarn org.jcodec.**
-dontwarn javax.jmdns.**
-dontwarn org.slf4j.**
