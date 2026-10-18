-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# kotlinx.serialization keeps generated serializers for our DTOs.
-keepclassmembers class ai.visionmirror.data.api.** { *** Companion; }
-keepclasseswithmembers class ai.visionmirror.data.api.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# MediaPipe (object detection for Guide mode): keep its JNI-facing classes, ignore optional protobuf extras.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
