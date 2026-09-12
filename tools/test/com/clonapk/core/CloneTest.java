package com.clonapk.core;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Uji end-to-end mesin clone di JVM (tanpa perangkat Android).
 *
 * <p>Jalur kode yang benar-benar dieksekusi: {@link Repackager#clone},
 * {@link ManifestEditor#changePackage}, {@link ApkSigner#sign}, dan
 * {@link ApkSigner#verifyV1}. Kalau ada yang rusak, program ini gagal keras.
 */
public final class CloneTest {

    private static int passed;
    private static int failed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        File workDir = new File(args.length > 0 ? args[0] : "build/clone-test");
        //noinspection ResultOfMethodCallIgnored
        workDir.mkdirs();

        ApkSigner.SigningKey key = ApkSigner.generateKey("CloneAPK Test");
        check("kunci penandatangan dibuat", key != null && key.certificate != null);

        testClone(key, workDir, "com.example.target", "com.clonapk.clone", 21, true,
                "string pool UTF-8");
        testClone(key, workDir, "com.vendor.camera.app", "com.vendor.camera.app.dual", 24, false,
                "string pool UTF-16 + minSdk 24");

        testDeepScanToggle(key, workDir);
        testStoredPreservedAndAligned(key, workDir);
        testEdgeCases(key, workDir);
        testSetMinSdkUtil();
        testValidation();
        testBase64();

        System.out.println();
        System.out.println("==================================================");
        System.out.println(" LULUS: " + passed + "   GAGAL: " + failed);
        if (failed > 0) {
            for (String f : failures) {
                System.out.println("  x " + f);
            }
            System.out.println("==================================================");
            System.exit(1);
        }
        System.out.println(" Semua uji mesin clone lulus.");
        System.out.println("==================================================");
    }

    private static void testClone(ApkSigner.SigningKey key, File workDir, String origPkg,
                                  String newPkg, int minSdk, boolean utf8, String label)
            throws Exception {
        System.out.println("\n--- Uji clone: " + label + " ---");
        File src = new File(workDir, "src-" + (utf8 ? "utf8" : "utf16") + ".apk");
        TestApkFactory.buildTestApk(src, origPkg, minSdk, utf8);
        check("APK uji dibuat (" + src.length() + " byte)", src.length() > 0);
        check("APK uji punya tanda tangan v1 asli", ApkSigner.verifyV1(read(src)));

        Repackager.ApkInfo info = Repackager.inspect(src);
        check("inspect membaca package asli", origPkg.equals(info.packageName));
        check("inspect membaca versionName", "1.7.3".equals(info.versionName));
        check("inspect membaca 2 permission", info.permissions.size() == 2);

        File outFile = new File(workDir, "out-" + (utf8 ? "utf8" : "utf16") + ".apk");
        final int[] lastPercent = {-1};
        Repackager.Result res = Repackager.clone(
                src, newPkg, workDir, outFile.getName(), key, false,
                (stage, percent) -> lastPercent[0] = percent);

        check("progress mencapai 100", lastPercent[0] == 100);
        check("berkas keluaran dibuat", res.outputFile.isFile());

        byte[] out = read(outFile);
        check("hasil lolos verifikasi tanda tangan v1", ApkSigner.verifyV1(out));

        byte[] outManifest = manifestOf(outFile);
        check("package hasil = " + newPkg,
                newPkg.equals(ManifestEditor.readPackage(outManifest)));

        String dump = dumpStrings(outManifest);
        check("activity utama ikut diganti",
                dump.contains(newPkg + ".MainActivity"));
        check("provider class ikut diganti",
                dump.contains(newPkg + ".provider.FileProvider"));
        check("authorities ikut diganti",
                dump.contains(newPkg + ".fileprovider"));
        check("permission kustom ikut diganti",
                dump.contains(newPkg + ".permission.C2D_MESSAGE"));
        // Package baru boleh saja mengandung nama lama sebagai prefix
        // (mis. com.x -> com.x.dual). Yang tidak boleh ada adalah sisa kelas
        // ber-prefix lama yang tidak ikut terganti.
        check("tidak ada sisa kelas ber-prefix package lama",
                !dump.contains(origPkg + ".MainActivity")
                        && !dump.contains(origPkg + ".provider")
                        && !dump.contains(origPkg + ".fileprovider")
                        && !dump.contains(origPkg + ".permission"));

        check("class library pihak ketiga TIDAK diubah",
                dump.contains("com.example.lib.SyncService"));
        check("action intent sistem TIDAK diubah",
                dump.contains("android.intent.action.MAIN"));
        check("nilai meta-data tidak ber-prefix package tetap utuh",
                dump.contains("u-9182736455"));
        check("versionName tetap terbaca",
                "1.7.3".equals(ManifestEditor.readVersionName(outManifest)));

        // Hasil kini ditandatangani v1+v2, jadi minSdk asli dipertahankan apa adanya.
        Integer outMinSdk = ManifestEditor.readMinSdk(outManifest);
        check("minSdk dipertahankan (" + minSdk + ")",
                outMinSdk != null && outMinSdk == minSdk);
        check("flag minSdkLowered = false", !res.minSdkLowered);
        check("hasil lolos verifikasi apksig (v1+v2)",
                ApkSigner.verifyApk(outFile));

        // Isi APK selain manifest harus tetap ada dan utuh.
        try (ZipFile zf = new ZipFile(outFile)) {
            check("classes.dex dipertahankan", zf.getEntry("classes.dex") != null);
            check("resources.arsc dipertahankan", zf.getEntry("resources.arsc") != null);
            check("res/layout dipertahankan", zf.getEntry("res/layout/main.xml") != null);
            check("assets dipertahankan", zf.getEntry("assets/data.json") != null);
            check("META-INF isi non-tandatangan dipertahankan",
                    zf.getEntry("META-INF/services/x") != null);
            check("MANIFEST.MF ada", zf.getEntry("META-INF/MANIFEST.MF") != null);
            check("berkas .SF penandatangan ada", hasEntryEnding(zf, ".SF"));
            check("berkas .RSA penandatangan ada", hasEntryEnding(zf, ".RSA"));

            byte[] dexOrig = entryOf(src, "classes.dex");
            byte[] dexNew = entryOf(outFile, "classes.dex");
            check("isi classes.dex identik bit-per-bit", java.util.Arrays.equals(dexOrig, dexNew));

            byte[] arscOrig = entryOf(src, "resources.arsc");
            byte[] arscNew = entryOf(outFile, "resources.arsc");
            check("isi resources.arsc identik", java.util.Arrays.equals(arscOrig, arscNew));
        }

        // Sertifikat hasil harus berbeda dari sertifikat asli (kunci clone sendiri).
        check("penandatangan berbeda dari APK asli",
                !sameSigner(src, outFile));
        check("sertifikat hasil bisa dibaca verifier JAR standar Java",
                signerOf(outFile) != null);
    }

    private static void testDeepScanToggle(ApkSigner.SigningKey key, File workDir)
            throws Exception {
        System.out.println("\n--- Uji deep-scan nilai meta-data ---");
        File src = new File(workDir, "src-deep.apk");
        byte[] manifest = TestApkFactory.buildManifest("com.deep.app", 21, true);
        java.util.LinkedHashMap<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("AndroidManifest.xml", manifest);
        entries.put("classes.dex", "dex".getBytes(StandardCharsets.UTF_8));
        java.io.FileOutputStream fos = new java.io.FileOutputStream(src);
        fos.write(ApkSigner.sign(entries, key));
        fos.close();

        // deep-scan MATI: nilai meta-data yang kebetulan ber-prefix package harus tetap
        Repackager.Result off = Repackager.clone(
                src, "com.deep.app.clone", workDir, "out-deep-off.apk", key, false, null);
        check("hasil deep-scan off lolos verifikasi",
                ApkSigner.verifyV1(read(off.outputFile)));
        check("package tetap terganti saat deep-scan off",
                "com.deep.app.clone".equals(
                        ManifestEditor.readPackage(manifestOf(off.outputFile))));

        // deep-scan NYALA
        Repackager.Result on = Repackager.clone(
                src, "com.deep.app.clone2", workDir, "out-deep-on.apk", key, true, null);
        check("hasil deep-scan on lolos verifikasi", ApkSigner.verifyV1(read(on.outputFile)));
        check("package tetap terganti saat deep-scan on",
                "com.deep.app.clone2".equals(
                        ManifestEditor.readPackage(manifestOf(on.outputFile))));
    }

    /**
     * APK modern menyimpan .so sebagai STORED (extractNativeLibs=false) dan
     * mensyaratkan penjajaran halaman. Uji: metode kompresi dipertahankan,
     * .so sejajar 16 KB, resources.arsc STORED sejajar 4 KB, isi identik,
     * tanda tangan tetap valid.
     */
    private static void testStoredPreservedAndAligned(ApkSigner.SigningKey key, File workDir)
            throws Exception {
        // Siapkan sumber: APK minimal dengan .so STORED, arsc STORED, dex DEFLATED.
        File base = new File(workDir, "stored-base.apk");
        TestApkFactory.buildTestApk(base, "com.stored.app", 21, true);
        byte[] manifest;
        try (ZipFile zf = new ZipFile(base)) {
            manifest = readAllBytes(zf.getInputStream(zf.getEntry("AndroidManifest.xml")));
        }
        java.util.Random rnd = new java.util.Random(42);
        byte[] soBytes = new byte[50000];
        rnd.nextBytes(soBytes);
        byte[] arscBytes = new byte[70000];
        rnd.nextBytes(arscBytes);
        byte[] dexBytes = new byte[30000];
        rnd.nextBytes(dexBytes);
        File src = new File(workDir, "stored-src.apk");
        try (java.util.zip.ZipOutputStream zos =
                     new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(src))) {
            ZipEntry mf = new ZipEntry("AndroidManifest.xml");
            zos.putNextEntry(mf);
            zos.write(manifest);
            zos.closeEntry();
            putStored(zos, "lib/x86_64/libnative.so", soBytes);
            putStored(zos, "resources.arsc", arscBytes);
            ZipEntry dex = new ZipEntry("classes.dex");
            zos.putNextEntry(dex);
            zos.write(dexBytes);
            zos.closeEntry();
        }

        File outDir = new File(workDir, "stored-out");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        Repackager.Result r = Repackager.clone(
                src, "com.stored.app.clone", outDir, "out.apk", key, false, null);

        try (ZipFile zf = new ZipFile(r.outputFile)) {
            check("STORED .so tetap STORED",
                    zf.getEntry("lib/x86_64/libnative.so").getMethod() == ZipEntry.STORED);
            check("STORED arsc tetap STORED",
                    zf.getEntry("resources.arsc").getMethod() == ZipEntry.STORED);
            check("DEFLATED dex tetap DEFLATED",
                    zf.getEntry("classes.dex").getMethod() == ZipEntry.DEFLATED);
            check("isi .so identik", java.util.Arrays.equals(
                    readAllBytes(zf.getInputStream(zf.getEntry("lib/x86_64/libnative.so"))),
                    soBytes));
            check("isi arsc identik", java.util.Arrays.equals(
                    readAllBytes(zf.getInputStream(zf.getEntry("resources.arsc"))),
                    arscBytes));
        }

        long soOff = dataOffset(r.outputFile, "lib/x86_64/libnative.so");
        long arscOff = dataOffset(r.outputFile, "resources.arsc");
        check("data .so sejajar 16 KB", soOff % Repackager.SO_ALIGN == 0);
        check("data arsc sejajar 4 KB", arscOff % Repackager.STORED_ALIGN == 0);
        check("tanda tangan clone valid", ApkSigner.verifyApk(r.outputFile));
    }

    private static void putStored(java.util.zip.ZipOutputStream zos, String name, byte[] data)
            throws java.io.IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        ZipEntry e = new ZipEntry(name);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        zos.putNextEntry(e);
        zos.write(data);
        zos.closeEntry();
    }

    /** Offset awal DATA sebuah entri (dihitung dari central + local header). */
    private static long dataOffset(File apk, String entryName) throws java.io.IOException {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(apk, "r")) {
            long len = raf.length();
            long p = len - 22;
            long minP = Math.max(0, len - 66000);
            byte[] sig = {0x50, 0x4b, 0x05, 0x06};
            byte[] b4 = new byte[4];
            while (p >= minP) {
                raf.seek(p);
                raf.readFully(b4);
                if (java.util.Arrays.equals(b4, sig)) {
                    break;
                }
                p--;
            }
            raf.seek(p + 16);
            byte[] cd = new byte[4];
            raf.readFully(cd);
            long cdOffset = (cd[0] & 0xffL) | ((cd[1] & 0xffL) << 8)
                    | ((cd[2] & 0xffL) << 16) | ((cd[3] & 0xffL) << 24);
            raf.seek(cdOffset);
            while (true) {
                byte[] hdr = new byte[46];
                raf.readFully(hdr);
                if (hdr[0] != 0x50 || hdr[1] != 0x4b || hdr[2] != 0x01 || hdr[3] != 0x02) {
                    break;
                }
                int nameLen = (hdr[28] & 0xff) | ((hdr[29] & 0xff) << 8);
                int extraLen = (hdr[30] & 0xff) | ((hdr[31] & 0xff) << 8);
                int commentLen = (hdr[32] & 0xff) | ((hdr[33] & 0xff) << 8);
                long locOff = (hdr[42] & 0xffL) | ((hdr[43] & 0xffL) << 8)
                        | ((hdr[44] & 0xffL) << 16) | ((hdr[45] & 0xffL) << 24);
                byte[] nb = new byte[nameLen];
                raf.readFully(nb);
                String nm = new String(nb, StandardCharsets.UTF_8);
                if (nm.equals(entryName)) {
                    raf.seek(locOff + 26);
                    byte[] l2 = new byte[4];
                    raf.readFully(l2);
                    int lName = (l2[0] & 0xff) | ((l2[1] & 0xff) << 8);
                    int lExtra = (l2[2] & 0xff) | ((l2[3] & 0xff) << 8);
                    return locOff + 30 + lName + lExtra;
                }
                raf.skipBytes(extraLen + commentLen);
            }
            throw new java.io.IOException("entri tidak ditemukan: " + entryName);
        }
    }

    private static byte[] readAllBytes(java.io.InputStream in) throws java.io.IOException {
        try (java.io.InputStream stream = in) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = stream.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    private static void testEdgeCases(ApkSigner.SigningKey key, File workDir) throws Exception {
        System.out.println("\n--- Uji kasus tepi ---");

        File notApk = TestApkFactory.buildEmptyZip(new File(workDir, "notapk.zip"));
        check("ZIP tanpa manifest ditolak", expectThrows(() -> Repackager.inspect(notApk)));

        File src = new File(workDir, "src-edge.apk");
        TestApkFactory.buildTestApk(src, "com.edge.app", 21, true);
        check("APK tepi dibuat", src.isFile());

        check("package sama dengan asli ditolak", expectThrows(() -> Repackager.clone(
                src, "com.edge.app", workDir, "x.apk", key, false, null)));
        check("package satu segmen ditolak", expectThrows(() -> Repackager.clone(
                src, "cumaninisaja", workDir, "x.apk", key, false, null)));
        check("package diawali android ditolak", expectThrows(() -> Repackager.clone(
                src, "android.hack.me", workDir, "x.apk", key, false, null)));
        check("segmen diawali angka ditolak", expectThrows(() -> Repackager.clone(
                src, "com.1bad.pkg", workDir, "x.apk", key, false, null)));
        check("kata kunci Java ditolak", expectThrows(() -> Repackager.clone(
                src, "com.class.app", workDir, "x.apk", key, false, null)));
        check("segmen kosong ditolak", expectThrows(() -> Repackager.clone(
                src, "com..app", workDir, "x.apk", key, false, null)));

        // clone dua kali harus menghasilkan APK yang sama-sama bisa dipasang
        Repackager.Result r1 = Repackager.clone(src, "com.edge.app.one", workDir,
                "edge1.apk", key, false, null);
        Repackager.Result r2 = Repackager.clone(src, "com.edge.app.two", workDir,
                "edge2.apk", key, false, null);
        check("clone pertama valid", ApkSigner.verifyV1(read(r1.outputFile)));
        check("clone kedua valid", ApkSigner.verifyV1(read(r2.outputFile)));
        check("dua clone punya package berbeda",
                !ManifestEditor.readPackage(manifestOf(r1.outputFile))
                        .equals(ManifestEditor.readPackage(manifestOf(r2.outputFile))));

        // penamaan keluaran
        check("suggestPackageName normal",
                "com.foo.bar.clone".equals(ApkUtil.suggestPackageName("com.foo.bar", "clone")));
        check("suggestPackageName membersihkan karakter aneh",
                "com.foo.bar.ku2".equals(ApkUtil.suggestPackageName("com.foo.bar", "ku 2!")));
        check("suggestPackageName tidak diawali angka",
                "com.foo.bar.c2".equals(ApkUtil.suggestPackageName("com.foo.bar", "2")));
        check("safeFileName membersihkan spasi",
                "Aplikasi_Saya.apk".equals(ApkUtil.safeFileName("Aplikasi Saya", "pkg")));
    }

    private static void testSetMinSdkUtil() {
        System.out.println("\n--- Uji util setMinSdk (dipertahankan untuk kebutuhan khusus) ---");
        byte[] m = TestApkFactory.buildManifest("com.minsdk.app", 30, true);
        byte[] lowered = ManifestEditor.setMinSdk(m, 23);
        check("setMinSdk mengubah 30 -> 23",
                ManifestEditor.readMinSdk(lowered) != null
                        && ManifestEditor.readMinSdk(lowered) == 23);
        // setMinSdk adalah setter apa adanya; penjagaan "hanya menurunkan"
        // menjadi tanggung jawab pemanggil.
        check("setMinSdk menyetel nilai persis (30 -> 34)",
                ManifestEditor.readMinSdk(ManifestEditor.setMinSdk(m, 34)) != null
                        && ManifestEditor.readMinSdk(ManifestEditor.setMinSdk(m, 34)) == 34);
    }

    private static void testValidation() {
        System.out.println("\n--- Uji deteksi manifest rusak ---");
        check("magic salah ditolak", expectThrows(() -> ManifestEditor.readPackage(
                new byte[] {1, 2, 3, 4, 5, 6, 7, 8})));
        byte[] good = TestApkFactory.buildManifest("com.trunc.app", 21, true);
        check("manifest utuh terbaca",
                "com.trunc.app".equals(ManifestEditor.readPackage(good)));

        byte[] truncated = new byte[good.length / 2];
        System.arraycopy(good, 0, truncated, 0, truncated.length);
        check("manifest terpotong ditolak (bukan hasil diam-diam salah)",
                expectThrows(() -> ManifestEditor.readPackage(truncated)));
    }

    private static void testBase64() {
        System.out.println("\n--- Uji encoder Base64 ---");
        java.util.Random rnd = new java.util.Random(20260912L);
        int checked = 0;
        for (int len = 0; len <= 300; len++) {
            byte[] data = new byte[len];
            rnd.nextBytes(data);
            String mine = ApkSigner.encodeBase64(data);
            String ref = wrap(java.util.Base64.getEncoder().encodeToString(data));
            if (!mine.equals(ref)) {
                check("base64 cocok dengan java.util.Base64 pada panjang " + len, false);
                return;
            }
            checked++;
        }
        check("base64 cocok dengan java.util.Base64 untuk panjang 0.." + (checked - 1), true);
        // Pemotongan baris penting: verifier JAR Android membaca .MF/.SF baris per baris.
        String[] lines = ApkSigner.encodeBase64(new byte[200]).split("\r\n");
        boolean allShort = true;
        for (String line : lines) {
            if (line.length() > 70) {
                allShort = false;
            }
        }
        check("base64 memotong baris tiap 70 karakter (" + lines.length + " baris)",
                allShort && lines.length > 1);
    }

    private static String wrap(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i += 70) {
            if (i > 0) {
                sb.append("\r\n");
            }
            sb.append(s, i, Math.min(s.length(), i + 70));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- utilities

    private interface Thrower {
        void run() throws Exception;
    }

    private static boolean expectThrows(Thrower t) {
        try {
            t.run();
            return false;
        } catch (Exception e) {
            System.out.println("    (ditolak sebagaimana diharapkan: " + first(e.getMessage()) + ")");
            return true;
        }
    }

    private static String first(String s) {
        if (s == null) {
            return "?";
        }
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    private static String dumpStrings(byte[] manifest) {
        StringBuilder sb = new StringBuilder();
        // cara kasar tapi cukup: baca semua teks yang bisa dibaca dari manifest
        int i = 0;
        StringBuilder cur = new StringBuilder();
        while (i < manifest.length) {
            byte b = manifest[i];
            if (b >= 32 && b < 127) {
                cur.append((char) b);
            } else {
                if (cur.length() >= 3) {
                    sb.append(cur).append('\n');
                }
                cur.setLength(0);
            }
            i++;
        }
        if (cur.length() >= 3) {
            sb.append(cur);
        }
        return sb.toString();
    }

    private static byte[] manifestOf(File apk) throws Exception {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry("AndroidManifest.xml");
            if (e == null) {
                throw new IllegalStateException("manifest tidak ada di " + apk);
            }
            return ApkSigner.read(zf, e);
        }
    }

    private static byte[] entryOf(File apk, String name) throws Exception {
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry e = zf.getEntry(name);
            return e == null ? null : ApkSigner.read(zf, e);
        }
    }

    private static boolean sameSigner(File a, File b) {
        try {
            java.security.cert.Certificate[] ca = signerOf(a);
            java.security.cert.Certificate[] cb = signerOf(b);
            if (ca == null || cb == null) {
                return false;
            }
            return java.util.Arrays.equals(ca[0].getEncoded(), cb[0].getEncoded());
        } catch (Exception e) {
            return false;
        }
    }

    private static java.security.cert.Certificate[] signerOf(File apk) throws Exception {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(apk)) {
            Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                java.util.jar.JarEntry e = en.nextElement();
                java.io.InputStream in = jf.getInputStream(e);
                byte[] buf = new byte[8192];
                while (in.read(buf) > 0) {
                    // membaca sampai habis memicu verifikasi sertifikat
                }
                in.close();
                java.security.cert.Certificate[] certs = e.getCertificates();
                if (certs != null && certs.length > 0) {
                    return certs;
                }
            }
        }
        return null;
    }

    private static boolean hasEntryEnding(java.util.zip.ZipFile zf, String suffix) {
        java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName().toUpperCase();
            if (n.startsWith("META-INF/") && n.endsWith(suffix.toUpperCase())) {
                return true;
            }
        }
        return false;
    }

    private static byte[] read(File f) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("  ok  " + name);
        } else {
            failed++;
            failures.add(name);
            System.out.println("  GAGAL  " + name);
        }
    }
}
