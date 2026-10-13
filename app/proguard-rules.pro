# kotlinx.serialization ships its own consumer rules; keep our @Serializable classes' companions/serializers.
-keepclassmembers @kotlinx.serialization.Serializable class dev.claudeusage.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class dev.claudeusage.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Never log in release.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
