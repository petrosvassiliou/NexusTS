# Keep JNI bridge entrypoints for FreeRDP 3.x native library
-keep class com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge {
    *;
}
-keepclasseswithmembernames,includedescriptorclasses class com.nexusts.app.protocols.rdp.** {
    native <methods>;
}
