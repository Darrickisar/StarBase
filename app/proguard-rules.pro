# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class StarBase.Android.Forum.**$$serializer { *; }
-keepclassmembers class StarBase.Android.Forum.** {
    *** Companion;
}
-keepclasseswithmembers class StarBase.Android.Forum.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class StarBase.Android.Forum.** {
    <fields>;
    <init>(...);
}
