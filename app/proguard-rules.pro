# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class com.clipforge.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
