# Keep kotlinx.serialization generated serializers.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.biketrailplanner.** {
    kotlinx.serialization.KSerializer serializer(...);
}
