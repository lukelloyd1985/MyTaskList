# Firebase / Firestore model classes are (de)serialized via reflection.
-keepclassmembers class com.mytasks.app.data.model.** {
    *;
}
-keep class com.mytasks.app.data.model.** { *; }
