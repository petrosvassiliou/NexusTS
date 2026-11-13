# Keep JNI bridge entrypoints and serialized snapshot DTOs for SSH/Terminal native library
-keep class com.nexusts.app.protocols.ssh.bridge.SshNativeBridge {
    *;
}
-keep class com.nexusts.app.protocols.ssh.bridge.TerminalSnapshotDto {
    *;
}
-keep class com.nexusts.app.protocols.ssh.bridge.TerminalSnapshotDto$$serializer {
    *;
}
-keep class com.nexusts.app.protocols.ssh.bridge.TerminalSnapshotDto$Companion {
    *;
}
-keepclasseswithmembernames,includedescriptorclasses class com.nexusts.app.protocols.ssh.** {
    native <methods>;
}
