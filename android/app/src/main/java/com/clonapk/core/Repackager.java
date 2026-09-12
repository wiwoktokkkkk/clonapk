package com.clonapk.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Mesin clone: membaca APK sumber, mengganti identitas package, lalu menandatangani ulang.
 *
 * <p>Langkah:
 * <ol>
 *   <li>Buka APK sumber, baca {@code AndroidManifest.xml} biner.</li>
 *   <li>Parse package asli, validasi package baru.</li>
 *   <li>Tulis ulang manifest dengan package baru ({@link ManifestEditor}).</li>
 *   <li>Turunkan {@code minSdkVersion} ke maks 23 kalau perlu, karena APK ini
 *       ditandatangani skema v1 saja.</li>
 *   <li>Buang semua berkas tanda tangan lama di {@code META-INF/}.</li>
 *   <li>Tandatangani ulang dengan kunci clone ({@link ApkSigner}).</li>
 * </ol>
 */
public final class Repackager {

    /** Batas minSdk yang aman untuk APK bertanda tangan v1 saja. */
    private static final int MAX_V1_ONLY_MIN_SDK = 23;

    private Repackager() {
    }

    /** Laporan kemajuan. */
    public interface ProgressListener {
        void onProgress(String stage, int percent);
    }

    /** Informasi APK sumber yang terbaca. */
    public static class ApkInfo {
        public String packageName;
        public String versionName;
        public long sizeBytes;
        public List<String> permissions = new ArrayList<>();

        @Override
        public String toString() {
            return packageName + " (" + versionName + ")";
        }
    }

    /** Hasil proses clone. */
    public static class Result {
        public final File outputFile;
        public final String newPackage;
        public final String originalPackage;
        public final boolean minSdkLowered;

        public Result(File outputFile, String newPackage, String originalPackage,
                      boolean minSdkLowered) {
            this.outputFile = outputFile;
            this.newPackage = newPackage;
            this.originalPackage = originalPackage;
            this.minSdkLowered = minSdkLowered;
        }
    }

    public static class CloneException extends RuntimeException {
        public CloneException(String msg) {
            super(msg);
        }

        public CloneException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /** Baca metadata dasar dari APK (untuk ditampilkan sebelum clone). */
    public static ApkInfo inspect(File apk) throws IOException {
        if (!apk.isFile()) {
            throw new CloneException("Berkas APK tidak ditemukan.");
        }
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry mf = zf.getEntry("AndroidManifest.xml");
            if (mf == null) {
                throw new CloneException(
                        "Berkas ini bukan APK yang valid (AndroidManifest.xml tidak ada).");
            }
            byte[] manifest = readAll(zf, mf);
            ApkInfo info = new ApkInfo();
            info.sizeBytes = apk.length();
            info.packageName = ManifestEditor.readPackage(manifest);
            info.versionName = ManifestEditor.readVersionName(manifest);
            info.permissions = ManifestEditor.collectPermissions(manifest);
            return info;
        }
    }

    /**
     * Jalankan proses clone.
     *
     * @param source          APK sumber
     * @param newPackage      applicationId baru
     * @param outputDir       direktori keluaran
     * @param outputName      nama berkas keluaran (tanpa path)
     * @param signingKey      kunci penandatangan
     * @param deepScanValues  ganti juga nilai meta-data ber-prefix package lama
     * @param progress        laporan kemajuan, boleh null
     */
    public static Result clone(File source, String newPackage, File outputDir, String outputName,
                               ApkSigner.SigningKey signingKey, boolean deepScanValues,
                               ProgressListener progress) throws IOException {
        report(progress, "Membaca APK sumber", 5);

        ApkUtil.validatePackageName(newPackage);
        if (!source.isFile()) {
            throw new CloneException("Berkas APK sumber tidak ditemukan.");
        }
        if (!outputDir.isDirectory() && !outputDir.mkdirs()) {
            throw new CloneException("Gagal membuat direktori keluaran: " + outputDir);
        }

        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        String originalPackage;
        boolean minSdkLowered = false;

        try (ZipFile zf = new ZipFile(source)) {
            ZipEntry manifestEntry = zf.getEntry("AndroidManifest.xml");
            if (manifestEntry == null) {
                throw new CloneException(
                        "Berkas ini bukan APK yang valid (AndroidManifest.xml tidak ada).");
            }
            byte[] manifestBytes = readAll(zf, manifestEntry);
            originalPackage = ManifestEditor.readPackage(manifestBytes);
            if (originalPackage.equals(newPackage)) {
                throw new CloneException(
                        "Package baru sama dengan package asli. Pilih nama yang berbeda.");
            }

            report(progress, "Menambal AndroidManifest.xml", 18);
            byte[] newManifest =
                    ManifestEditor.changePackage(manifestBytes, originalPackage, newPackage,
                            deepScanValues);

            report(progress, "Menyesuaikan minSdkVersion", 24);
            Integer currentMinSdk = ManifestEditor.readMinSdk(newManifest);
            if (currentMinSdk != null && currentMinSdk > MAX_V1_ONLY_MIN_SDK) {
                newManifest = ManifestEditor.setMinSdk(newManifest, MAX_V1_ONLY_MIN_SDK);
                minSdkLowered = true;
            }

            Enumeration<? extends ZipEntry> en = zf.entries();
            List<ZipEntry> list = new ArrayList<>();
            while (en.hasMoreElements()) {
                list.add(en.nextElement());
            }
            int total = Math.max(1, list.size());
            int i = 0;
            for (ZipEntry ze : list) {
                String name = ze.getName();
                i++;
                if (isOldSignature(name)) {
                    continue;
                }
                if (name.equals("AndroidManifest.xml")) {
                    entries.put(name, newManifest);
                } else if (ze.isDirectory()) {
                    entries.put(name, null);
                } else {
                    byte[] data = readAll(zf, ze);
                    entries.put(name, data);
                }
                if (i % 8 == 0 || i == total) {
                    report(progress, "Menyalin isi APK",
                            25 + (int) (i * 45.0 / total));
                }
            }
        }

        report(progress, "Menandatangani ulang", 78);
        byte[] signed = ApkSigner.sign(entries, signingKey);

        report(progress, "Memverifikasi tanda tangan", 90);
        if (!ApkSigner.verifyV1(signed)) {
            throw new CloneException(
                    "Verifikasi tanda tangan gagal. APK tidak ditulis agar Anda tidak "
                            + "memasang berkas rusak.");
        }
        String writtenPkg = ManifestEditor.readPackage(extractManifest(signed));
        if (!newPackage.equals(writtenPkg)) {
            throw new CloneException(
                    "Package pada hasil tidak cocok: " + writtenPkg);
        }

        File out = new File(outputDir, outputName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(signed);
        }
        report(progress, "Selesai", 100);
        return new Result(out, newPackage, originalPackage, minSdkLowered);
    }

    private static byte[] extractManifest(byte[] apk) throws IOException {
        ApkSigner.TmpFile tmp = new ApkSigner.TmpFile(apk);
        try (ZipFile zf = new ZipFile(tmp.file())) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) {
                throw new CloneException("AndroidManifest.xml hilang dari hasil clone");
            }
            return readAll(zf, e);
        } finally {
            tmp.close();
        }
    }

    /**
     * Berkas tanda tangan lama wajib dibuang: MANIFEST.MF/SF/RSA/DSA/EC yang tidak cocok
     * akan membuat instalasi menolak APK dengan INSTALL_PARSE_FAILED_NO_CERTIFICATES.
     */
    static boolean isOldSignature(String name) {
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        String upper = name.toUpperCase();
        return upper.endsWith("MANIFEST.MF")
                || upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC");
    }

    private static byte[] readAll(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    Math.max(1024, (int) e.getSize()));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void report(ProgressListener l, String stage, int percent) {
        if (l != null) {
            l.onProgress(stage, Math.max(0, Math.min(100, percent)));
        }
    }
}
