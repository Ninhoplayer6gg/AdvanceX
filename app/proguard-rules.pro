# SPDX-License-Identifier: MPL-2.0
# R8 rules for release builds.

# JNI: native methods are bound by name in JNI_OnLoad (RegisterNatives), so
# the bridge class and its method names must survive shrinking/obfuscation.
-keep class io.advancex.app.bridge.NativeBridge { *; }

# kotlinx.serialization: the library ships its own rules; these keep the
# generated serializers of AdvanceX's @Serializable model classes reachable.
-keepclassmembers @kotlinx.serialization.Serializable class io.advancex.** {
    *** Companion;
}
-keepclasseswithmembers class io.advancex.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.advancex.**$$serializer { *; }

# Activities are referenced from the manifest (kept by default); nothing is
# loaded by reflection elsewhere.
