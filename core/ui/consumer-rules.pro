# R8 / ProGuard Consumer Rules for :core:ui
# Preserve @Serializable workspace tiling, tab state, and keyboard shortcut models under R8 Full Mode.

-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature

-keepclassmembers class com.nexusts.app.core.ui.** {
    companion object;
}

-keepclasseswithmembers class com.nexusts.app.core.ui.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.nexusts.app.core.ui.**$$serializer { *; }

-keep class com.nexusts.app.core.ui.workspace.WorkspaceSessionTab { *; }
-keep class com.nexusts.app.core.ui.workspace.PaneNode { *; }
-keep class com.nexusts.app.core.ui.workspace.PaneNode$* { *; }
-keep class com.nexusts.app.core.ui.workspace.SessionProtocolKind { *; }
-keep class com.nexusts.app.core.ui.workspace.SessionLifecycleStatus { *; }
-keep class com.nexusts.app.core.ui.workspace.SplitOrientation { *; }
-keep class com.nexusts.app.core.ui.input.NexusShortcutAction { *; }
-keep class com.nexusts.app.core.ui.input.KeyChord { *; }
-keep class com.nexusts.app.core.ui.input.ShortcutBinding { *; }
