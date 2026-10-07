-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
# kotlinx.serialization keeps generated serializers for our DTOs.
-keepclassmembers class ai.visionmirror.data.api.** { *** Companion; }
-keepclasseswithmembers class ai.visionmirror.data.api.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
