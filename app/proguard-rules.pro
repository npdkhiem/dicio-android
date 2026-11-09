# R8 keep rules for the shrunk build type. Each block says why it is here.
# Do not add a blanket keep of org.stypox.dicio.** or -dontobfuscate / -dontshrink.

# JNA, used by Vosk (libjnidispatch). Native code looks up these Java classes,
# fields, and callbacks by name. "**" covers subpackages (com.sun.jna.ptr and
# others); the old "com.sun.jna.*" rule only matched the top package.
# java.awt is referenced by JNA and is not on Android.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.**

# Vosk. Its JNA Library interfaces and Callbacks are invoked through reflection
# and dynamic proxies, so the class and member names must stay.
-keep class org.vosk.** { *; }

# TFLite / LiteRT (OpenWakeWord). The JNI library calls Java methods by name.
# LiteRT ships some consumer rules; keeping the package is the reliable fix.
# -dontwarn covers optional classes the AAR references but does not ship.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Protobuf-lite + DataStore. GeneratedMessageLite reads message fields by name
# (the schema string array in dynamicMethod, e.g. "dynamicColors_" on
# UserSettings). Renaming a field makes stored settings fail to parse.
# protobuf-javalite also ships consumer rules; this one is explicit so settings
# messages cannot be renamed out from under a saved DataStore file.
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }

# kotlinx.serialization, for the @Serializable navigation routes in
# org.stypox.dicio.ui.nav.Routes (Home, MainSettings, SkillSettings, About).
# The library has shipped consumer rules since 1.5. These are the official
# app-side rules from kotlinx.serialization 1.9.0 rules/common.pro and
# rules/r8.pro, limited to this app's classes, for R8 full mode:
# Navigation looks up Companion.serializer() / INSTANCE.serializer() by name,
# and full mode strips the runtime @Serializable annotation those lookups use.
# https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/rules/common.pro
# https://github.com/Kotlin/kotlinx.serialization/blob/v1.9.0/rules/r8.pro

# Keep the Serializable annotation on the class (R8 full mode removes it).
-if @kotlinx.serialization.Serializable class org.stypox.dicio.**
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class <1>

# Companion field, so serializer lookup does not walk getDeclaredClasses.
-if @kotlinx.serialization.Serializable class org.stypox.dicio.**
-keepclassmembers class <1> {
    static <1>$* Companion;
}

# serializer() on the companion (default or named).
-if @kotlinx.serialization.Serializable class org.stypox.dicio.** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# INSTANCE.serializer() on @Serializable objects.
-if @kotlinx.serialization.Serializable class org.stypox.dicio.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# Same INSTANCE + serializer() keep, written the way rules/r8.pro states it.
-keepclassmembers @kotlinx.serialization.Serializable class org.stypox.dicio.** {
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

# The generated serializer's descriptor field. Some ProGuard versions break it
# when optimizing (kotlinx.serialization issue 2719).
-keepclassmembers class org.stypox.dicio.**$$serializer {
    private ** descriptor;
}

# @Serializable is read at runtime. The library consumer rules already keep
# these attributes globally; repeated here so the app file is enough to explain
# why the route objects still carry the annotation.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# dicio-numbers. Small, and it loads per-language data as Java resources
# (config/<locale>/*.json and *.word) via Class.getResourceAsStream. Keeping
# the classes stops R8 from renaming types around that lookup. The resource
# files themselves are classpath entries, not Android res/, so resource
# shrinking does not drop them; the keep is the safe half of that pair.
-keep class org.dicio.numbers.** { *; }

# Generated skill sentences. Skills call org.stypox.dicio.sentences.Sentences
# directly; nothing loads it by Class.forName or a resource name. The first
# shrunk build renamed it to rc.s1. -keepnames keeps that class name when the
# class is used, and still lets R8 remove unused nested types and members.
# A -keep { *; } would pin the whole generated grammar and undo a lot of shrinking.
-keepnames class org.stypox.dicio.sentences.Sentences

# OkHttp 5 already ships -dontwarn for org.conscrypt.**, org.bouncycastle.**,
# and okhttp3.internal.platform.**. assembleShrunk reported no missing classes
# (no missing_rules.txt, no org.openjsse warning), so no extra -dontwarn is added.
