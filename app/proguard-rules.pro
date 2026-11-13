# Keep JNI entrypoints across Rust & C++ FreeRDP bridges
-keepclasseswithmembernames class com.nexusts.app.** {
    native <methods>;
}
-keep class com.nexusts.app.protocols.rdp.bridge.RdpSessionCallbacks { *; }
-keep class com.nexusts.app.protocols.ssh.bridge.SshSessionCallbacks { *; }
-keep class com.nexusts.app.protocols.vnc.bridge.VncSessionCallbacks { *; }

# Keep Kotlinx Serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class com.nexusts.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Google API Client & Drive v3 models
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class * extends com.google.api.client.json.GenericJson { *; }

# SQLCipher 4.6.1 JNI (16 KB page-aligned AAR)
-keep class net.zetetic.** { *; }
-keep class net.sqlcipher.** { *; }

# Suppress optional JVM-only / JNDI / Conscrypt / OpenCensus references in BouncyCastle, Google HTTP Client & OkHttp
-dontwarn javax.naming.**
-dontwarn org.bouncycastle.**
-dontwarn org.apache.http.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn io.grpc.**
-dontwarn io.opencensus.**
-dontwarn com.google.appengine.**
-dontwarn com.google.apphosting.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**

