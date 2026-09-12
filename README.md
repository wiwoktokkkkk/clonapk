# ClonApk

Pembuat salinan (clone) aplikasi Android dengan antarmuka bergaya iOS.

ClonApk mengambil sebuah APK, mengganti identitasnya (applicationId, otoritas
provider, permission kustom, referensi kelas) sehingga salinannya bisa dipasang
**berdampingan** dengan aplikasi aslinya, lalu menandatangani ulang salinan itu
dengan kunci yang dibuat aplikasi. UI-nya memakai widget Cupertino penuh —
daftar inset bergrup, lembar bawah, animasi, dan tipografi — supaya terasa
seperti aplikasi iOS di atas Android.

![Ikon](art/icon_1024.png)

## Fitur

- Daftar aplikasi terpasang (dengan pencarian dan opsi aplikasi sistem) untuk
  dipilih sebagai sumber clone.
- Memilih berkas APK dari penyimpanan bila APK tidak terdaftar.
- Pengaturan identitas clone: nama tampilan, package baru, akhiran otomatis,
  validasi nama package ala Android (kata kunci Java, awalan `android`, dan
  sebagainya ditolak sebelum proses dimulai).
- Mesin clone berjalan di latar dengan laporan kemajuan langsung (stage + %).
- Hasil bisa dipasang, dibagikan, atau dihapus; riwayat clone tersimpan.
- Mode gelap/terang mengikuti sistem.

## Cara kerja mesin clone

1. **Baca `AndroidManifest.xml` biner (AXML).** Manifest di dalam APK bukan
   teks; ia string pool + chunk XML biner. `ManifestEditor` mem-parse format
   itu (UTF-8 maupun UTF-16) secara penuh.
2. **Ganti identitas** dalam satu tahap atas snapshot nilai asli: atribut
   `package`, `android:authorities`, `android:targetPackage`,
   `android:permission`, `android:process`, `android:name` yang merujuk package
   lama, plus entri string pool (kelas komponen fully-qualified). Opsi lanjutan
   bisa ikut mengganti nilai `meta-data` yang berawalan package lama.
3. **Turunkan `minSdkVersion` ke 23** bila perlu, karena hasil ditandatangani
   skema v1.
4. **Buang tanda tangan lama** (`META-INF/*.SF/.RSA/.DSA/MANIFEST.MF`) lalu
   **tandatangani ulang skema v1** (SHA-256) dengan sertifikat self-signed baru
   (BouncyCastle). Hasil diverifikasi sebelum ditulis ke penyimpanan.

### Batasan yang perlu diketahui

- Skema v1 berarti perangkat Android 7+ tetap bisa memasang, tetapi APK yang
  `minSdkVersion` aslinya > 23 akan diturunkan otomatis ke 23 (perilaku ini
  dilaporkan di layar hasil).
- Kode aplikasi (DEX) tidak diubah. Aplikasi yang memeriksa identitas dirinya
  sendiri secara eksplisit mungkin tetap mengenali dirinya; begitu juga layanan
  pihak ketiga yang mengikat identitas package (Firebase/Play Services) —
  karena itu opsi "ganti menyeluruh" dimatikan secara bawaan.
- Gunakan hanya untuk aplikasi yang Anda miliki haknya (uji pengembangan
  sendiri, dual-akun aplikasi Anda sendiri, dan sebagainya).

## Uji mesin (tanpa perangkat)

Inti clone adalah pure Java dan diuji end-to-end di JVM: pembuat APK uji
menulis manifest AXML sungguhan (varian UTF-8 & UTF-16), lalu proses clone
dijalankan dan hasilnya diverifikasi — package terganti, referensi lama hilang,
kelas pihak ketiga utuh, isi DEX identik bit-per-bit, dan tanda tangan v1
terbaca oleh verifier JAR standar Java.

```bash
./run-core-tests.sh
```

Butuh JDK 11+; BouncyCastle diunduh otomatis sekali oleh skrip itu.

## Membangun APK

```bash
flutter pub get
flutter build apk --debug      # atau --release
```

Versi yang dipakai saat proyek ditulis: Flutter ≥ 3.38 (Dart ≥ 3.10), AGP 8.7.3,
Kotlin 2.1.0, Gradle 8.10.2.

## Struktur

```
android/          proyek Android + plugin Flutter (Kotlin) + inti clone (Java)
lib/              kode Dart: layar & widget Cupertino
tools/test/       pembuat APK uji + runner uji JVM
run-core-tests.sh skrip uji mesin clone
art/              master ikon aplikasi
```

## Lisensi

MIT — lihat [LICENSE](LICENSE).
