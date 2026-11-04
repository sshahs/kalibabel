# kotlinx.serialization: keep generated serializers for @Serializable classes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers @kotlinx.serialization.Serializable class email.sshah.kalibabel.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class email.sshah.kalibabel.**$$serializer { *; }

# OkHttp ships its own consumer rules; silence optional platform warnings.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# ML Kit (and Firebase) discover ComponentRegistrar classes from manifest meta-data and create them
# with newInstance(). R8 full mode does not keep no-arg constructors for plain "-keep class" rules,
# which silently dropped every registrar in v0.1.0 (on-device translation NPE, language ID broken).
# scripts/ci/check-r8-reflection.py guards against this class of bug in CI.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
