# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep Room database classes
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Keep ZXing classes
-keep class com.google.zxing.** { *; }
-keep class com.journeyapps.barcodescanner.** { *; }

# Keep cryptography classes
-keep class com.p2pshare.app.crypto.** { *; }

# Keep data classes for JSON serialization
-keep class com.p2pshare.app.transfer.FileManifest { *; }
-keep class com.p2pshare.app.transfer.SessionInfo { *; }

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep CameraX classes
-keep class androidx.camera.** { *; }