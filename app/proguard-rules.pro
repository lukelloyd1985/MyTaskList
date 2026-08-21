# Firebase / Firestore model classes are (de)serialized via reflection.
-keepclassmembers class com.mytasks.app.data.model.** {
    *;
}
-keep class com.mytasks.app.data.model.** { *; }

# AppAuth
-keep class net.openid.appauth.** { *; }

# Facebook SDK
-keep class com.facebook.** { *; }
