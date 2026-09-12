# Menjaga kelas BouncyCastle yang diakses lewat refleksi (CMS/PKCS#7).
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# Jaga inti mesin clone.
-keep class com.clonapk.core.** { *; }
