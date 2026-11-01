
# LiteRT-LM: the native runtime resolves these Java classes, fields and methods by name over JNI. If R8 renames or
# strips them the native code aborts the process (SIGABRT) with no Java stack trace. Release-only crash otherwise.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**

# ML Kit (Translate): RemoteModelManager and the translator impls are wired up reflectively through the component
# registrars (MlKitComponentDiscoveryService reads them from the manifest). Without these keeps R8 mangles the
# registrations and RemoteModelManager.isModelDownloaded() NPEs in release builds only.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_translate.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
-keep class com.google.firebase.components.** { *; }
-dontwarn com.google.mlkit.**
