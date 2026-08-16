# Lumenberg keeps almost nothing: no reflection, no serialisation library, no DI graph.
# The only names that must survive are the ones the framework looks up by string.

# org.json ships in the platform, not the APK.
-dontwarn org.json.**

# Kotlin coroutines' internal service loading.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
