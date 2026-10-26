# Keep kotlinx.serialization generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.daepilot.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.daepilot.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# SnakeYAML uses reflection for JavaBean properties.
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
