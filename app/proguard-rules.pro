
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

# Hilt resolves @HiltViewModel classes by their *name* string at runtime (HiltViewModelFactory). If R8 renames
# the class (e.g. PlayerViewModel -> qz3) the lookup misses, the factory falls back to reflection and the app
# dies with "Cannot create an instance of class qz3 / NoSuchMethodException: <init> []". Keep the names.
-keepnames class * extends androidx.lifecycle.ViewModel
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class *
# Hilt-generated ViewModel bindings/keys are only reachable reflectively (name -> provider map in HiltViewModelFactory).
-keep class **_HiltModules { *; }
-keep class **_HiltModules$* { *; }
-keep class **_HiltModules_*_LazyMapKey { *; }
-keep class **_Factory_Impl { *; }
