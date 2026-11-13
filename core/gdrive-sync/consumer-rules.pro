# Preserve annotations and generic signatures required by Google HTTP/Drive Client & Room
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses, EnclosingMethod

# Critical: Never rename @Key fields on HttpHeaders (e.g. authorization), DriveRequest query params, or JSON models
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}

# Google Drive API v3 & Google HTTP Client reflective models
-keep class com.google.api.services.drive.** {
    *;
}
-keep class * extends com.google.api.client.json.GenericJson {
    *;
}
-keep class * extends com.google.api.client.util.GenericData {
    *;
}

# Room & SQLCipher encrypted sync metadata database
-keep class * extends androidx.room.RoomDatabase {
    <init>();
    *;
}
-keep class com.nexusts.app.core.gdrivesync.cache.** {
    *;
}
-keep class net.zetetic.** {
    *;
}
-keep class net.sqlcipher.** {
    *;
}
-dontwarn net.zetetic.**
-dontwarn net.sqlcipher.**

# Hilt / WorkManager background sync worker
-keep class com.nexusts.app.core.gdrivesync.sync.VaultDriveSyncWorker {
    *;
}
