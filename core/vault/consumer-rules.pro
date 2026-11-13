# JNI Bridge to Rust nexusts-crypto (libnexusts_jni.so)
-keep class com.nexusts.app.core.vault.crypto.NativeVaultCryptoBridge {
    *;
}

# Official kotlinx.serialization R8 Full-Mode Keep Rules
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepnames class <2>$$<3>

-if @kotlinx.serialization.Serializable class **
-keep class <1>$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class **$$serializer {
    static **$$serializer INSTANCE;
}

-keep class * implements kotlinx.serialization.internal.GeneratedSerializer {
    *;
}

-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}

# Keep all NexusTS Vault serializable models, sealed settings hierarchies, and enums
-keep class com.nexusts.app.core.vault.model.** {
    *;
}
-keepclassmembers enum com.nexusts.app.core.vault.model.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep @kotlinx.serialization.Serializable class com.nexusts.app.core.vault.** {
    *;
}
