# Keep JNI entrypoints across Rust & C++ FreeRDP bridges
-keep class com.nexusts.app.core.vault.crypto.NativeVaultCryptoBridge { *; }
-keep class com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge { *; }
-keep class com.nexusts.app.protocols.rdp.storage.NexusRdpClipboardDocumentsProvider { *; }
-keep class com.nexusts.app.protocols.ssh.bridge.SshNativeBridge { *; }
-keep class com.nexusts.app.protocols.vnc.bridge.VncNativeBridge { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.nexusts.app.** {
    native <methods>;
}

# Keep Kotlinx Serialization generated serializers & polymorphic hierarchies (R8 Full Mode)
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers class com.nexusts.app.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class * implements kotlinx.serialization.internal.GeneratedSerializer {
    *;
}

-keep class com.nexusts.app.core.vault.model.** { *; }
-keep class com.nexusts.app.core.ui.workspace.WorkspaceSessionTab { *; }
-keep class com.nexusts.app.core.ui.workspace.SessionProtocolKind { *; }
-keep class com.nexusts.app.core.ui.workspace.SessionLifecycleStatus { *; }

# Google HTTP Java Client (@Key reflection on HttpHeaders, DriveRequest & GenericData) + Drive v3 models
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-keep class com.google.api.services.drive.** { *; }
-keepclassmembers class * extends com.google.api.client.json.GenericJson { *; }
-keepclassmembers class * extends com.google.api.client.util.GenericData { *; }

# Room, Hilt WorkManager & SQLCipher 4.6.1 JNI (16 KB page-aligned AAR)
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class com.nexusts.app.core.gdrivesync.sync.VaultDriveSyncWorker { *; }
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
