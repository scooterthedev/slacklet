# Retrofit interfaces are implemented reflectively at runtime.
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations

# kotlinx.serialization generates serializers as nested classes; R8 cannot see the link.
-keepclassmembers class ** {
    *** Companion;
}
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.scooter.slackwear.**$$serializer { *; }

# Firebase Messaging resolves the service by name from the manifest.
-keep class com.scooter.slackwear.push.** { *; }

# Wear's remote auth passes these across the Data Layer.
-keep class androidx.wear.phone.interactions.authentication.** { *; }

# Coil finds its decoders and network fetcher through ServiceLoader. R8 rewrote the
# META-INF/services entries into self-referential nonsense, leaving the release build with
# no network fetcher and therefore no remote images at all. SlackImageLoader now registers
# the fetcher by hand, and these keep the discovery path intact as well.
-keep class coil3.util.** { *; }
-keep class coil3.network.okhttp.** { *; }
-keep class coil3.gif.** { *; }
