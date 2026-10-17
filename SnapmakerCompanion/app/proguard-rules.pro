# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keep,includedescriptorclasses class pl.u1companion.**$$serializer { *; }
-keepclassmembers class pl.u1companion.** {
    *** Companion;
}
-keepclasseswithmembers class pl.u1companion.** {
    kotlinx.serialization.KSerializer serializer(...);
}
