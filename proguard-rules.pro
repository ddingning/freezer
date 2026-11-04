# Keep the app code (ADB protocol/pairing, providers, services, activities).
-keep class com.frostbyte.freezer.** { *; }

# Device admin receiver is referenced from the manifest.
-keep class com.frostbyte.freezer.admin.** { *; }

# Shizuku API and its AIDL proxies.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.server.** { *; }

# BouncyCastle and Conscrypt contain optional platform/LDAP adapters that are not
# present in the Android compile SDK. The actually used subset is linked directly.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn com.android.org.conscrypt.**
-dontwarn org.apache.harmony.**
-dontwarn javax.naming.**
-dontwarn org.slf4j.**
