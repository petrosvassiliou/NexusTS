# Keep JNI bridge entrypoints and serialized DTOs for VNC & Network Utilities native library
-keep class com.nexusts.app.protocols.vnc.bridge.VncNativeBridge {
    *;
}
-keep class com.nexusts.app.protocols.vnc.bridge.PortProbeResult {
    *;
}
-keep class com.nexusts.app.protocols.vnc.bridge.PortProbeResult$$serializer {
    *;
}
-keep class com.nexusts.app.protocols.vnc.bridge.PortProbeResult$Companion {
    *;
}
-keep class com.nexusts.app.protocols.vnc.bridge.NativeTracerouteHopDto {
    *;
}
-keep class com.nexusts.app.protocols.vnc.bridge.NativeTracerouteHopDto$$serializer {
    *;
}
-keep class com.nexusts.app.protocols.vnc.bridge.NativeTracerouteHopDto$Companion {
    *;
}
-keepclasseswithmembernames,includedescriptorclasses class com.nexusts.app.protocols.vnc.** {
    native <methods>;
}
