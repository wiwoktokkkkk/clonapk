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
import java.util.zip.ZipOutputStream;

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

        // Tahap 1: baca + tambal manifest. Hanya manifest yang disangga utuh
        // (ukurannya kecil); isi APK lain tidak pernah dimuat penuh ke memori.
        String originalPackage;
        byte[] newManifest;
        int totalEntries;
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
            newManifest = ManifestEditor.changePackage(
                    manifestBytes, originalPackage, newPackage, deepScanValues);

            totalEntries = countEntries(zf);
        }

        // Tahap 2: salin isi ke zip sementara (tanpa tanda tangan lama), lalu
        // tandatangani berkas-ke-berkas. Memori tetap konstan berapa pun ukuran APK.
        File tmpUnsigned = File.createTempFile("clonapk-unsigned", ".apk");
        try {
            try (ZipFile zf = new ZipFile(source);
                 ZipOutputStream zos =
                         new ZipOutputStream(new FileOutputStream(tmpUnsigned))) {
                int i = 0;
                Enumeration<? extends ZipEntry> en = zf.entries();
                while (en.hasMoreElements()) {
                    ZipEntry ze = en.nextElement();
                    String name = ze.getName();
                    i++;
                    if (isOldSignature(name)) {
                        continue;
                    }
                    if (name.equals("AndroidManifest.xml")) {
                        zos.putNextEntry(new ZipEntry(name));
                        zos.write(newManifest);
                        zos.closeEntry();
                    } else if (ze.isDirectory()) {
                        ZipEntry dir = new ZipEntry(name);
                        dir.setMethod(ZipEntry.STORED);
                        dir.setSize(0);
                        dir.setCompressedSize(0);
                        dir.setCrc(0);
                        zos.putNextEntry(dir);
                        zos.closeEntry();
                    } else {
                        zos.putNextEntry(new ZipEntry(name));
                        copyEntry(zf, ze, zos);
                        zos.closeEntry();
                    }
                    if (i % 8 == 0 || i == totalEntries) {
                        report(progress, "Menyalin isi APK",
                                25 + (int) (i * 45.0 / totalEntries));
                    }
                }
            }

            report(progress, "Menandatangani ulang (v1+v2)", 78);
            File out = new File(outputDir, outputName);
            ApkSigner.signApk(tmpUnsigned, out, signingKey);

            report(progress, "Memverifikasi tanda tangan", 90);
            if (!ApkSigner.verifyApk(out)) {
                throw new CloneException(
                        "Verifikasi tanda tangan gagal. APK tidak ditulis agar Anda tidak "
                                + "memasang berkas rusak.");
            }
            String writtenPkg = ManifestEditor.readPackage(extractManifest(out));
            if (!newPackage.equals(writtenPkg)) {
                throw new CloneException(
                        "Package pada hasil tidak cocok: " + writtenPkg);
            }
            report(progress, "Selesai", 100);
            return new Result(out, newPackage, originalPackage, false);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmpUnsigned.delete();
        }
    }

    private static byte[] extractManifest(File apk) throws IOException {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) {
                throw new CloneException("AndroidManifest.xml hilang dari hasil clone");
            }
            return readAll(zf, e);
        }
    }

    private static int countEntries(ZipFile zf) {
        int n = 0;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            en.nextElement();
            n++;
        }
        return Math.max(1, n);
    }

    private static void copyEntry(ZipFile zf, ZipEntry ze, ZipOutputStream zos)
            throws IOException {
        try (InputStream in = zf.getInputStream(ze)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
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
