
# LiteRT-LM: the native runtime resolves these Java classes, fields and methods by name over JNI. If R8 renames or
# strips them the native code aborts the process (SIGABRT) with no Java stack trace. Release-only crash otherwise.
-keep class com.google.ai.edge.litertlm.** { *; }
-keep interface com.google.ai.edge.litertlm.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-dontwarn com.google.ai.edge.litertlm.**
