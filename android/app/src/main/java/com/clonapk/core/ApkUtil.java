package com.clonapk.core;

import java.util.regex.Pattern;

/** Utilitas validasi dan penamaan untuk proses clone. */
public final class ApkUtil {

    /**
     * Aturan nama package Android: minimal dua segmen, tiap segmen diawali huruf,
     * hanya boleh [A-Za-z0-9_], dan bukan kata kunci Java.
     * Sumber: dokumentasi resmi {@code applicationId} Android.
     */
    private static final Pattern SEGMENT =
            Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    private static final String[] RESERVED = {
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new",
        "package", "private", "protected", "public", "return", "short", "static",
        "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while", "true", "false", "null"
    };

    private ApkUtil() {
    }

    /**
     * Pastikan nama package valid untuk dijadikan applicationId.
     *
     * @throws IllegalArgumentException dengan pesan yang bisa langsung ditampilkan ke pengguna
     */
    public static void validatePackageName(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            throw new IllegalArgumentException("Nama package tidak boleh kosong.");
        }
        if (pkg.length() > 200) {
            throw new IllegalArgumentException("Nama package terlalu panjang (maks 200 karakter).");
        }
        if (pkg.startsWith("android")) {
            throw new IllegalArgumentException(
                    "Nama package tidak boleh diawali 'android' — dicadangkan sistem.");
        }
        String[] parts = pkg.split("\\.", -1);
        if (parts.length < 2) {
            throw new IllegalArgumentException(
                    "Nama package butuh minimal dua segmen, contoh: com.clonapk.clone");
        }
        for (String p : parts) {
            if (!SEGMENT.matcher(p).matches()) {
                throw new IllegalArgumentException(
                        "Segmen '" + p + "' tidak valid. Tiap segmen harus diawali huruf "
                                + "dan hanya berisi huruf, angka, atau underscore.");
            }
            for (String r : RESERVED) {
                if (p.equals(r)) {
                    throw new IllegalArgumentException(
                            "Segmen '" + p + "' adalah kata kunci Java dan tidak bisa dipakai.");
                }
            }
        }
    }

    /**
     * Usulkan nama package clone dari package asli.
     * Contoh: {@code com.foo.bar} -> {@code com.foo.bar.clone}
     */
    public static String suggestPackageName(String original, String suffix) {
        String s = (suffix == null || suffix.trim().isEmpty()) ? "clone" : suffix.trim();
        String cleaned = s.toLowerCase().replaceAll("[^a-z0-9_]", "");
        if (cleaned.isEmpty()) {
            cleaned = "clone";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "c" + cleaned;
        }
        return original + "." + cleaned;
    }

    /** Nama file output yang aman untuk filesystem. */
    public static String safeFileName(String label, String pkg) {
        String base = (label == null || label.trim().isEmpty()) ? pkg : label.trim();
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (cleaned.length() > 60) {
            cleaned = cleaned.substring(0, 60);
        }
        return cleaned + ".apk";
    }
}
