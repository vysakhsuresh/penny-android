# SQLCipher native bindings
-keep class net.zetetic.database.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.penny.** { *** Companion; }
-keepclasseswithmembers class app.penny.** { kotlinx.serialization.KSerializer serializer(...); }
