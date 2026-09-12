package com.clonapk.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;

/**
 * Penandatangan APK skema v1 (JAR signing).
 *
 * <p>Kenapa v1, bukan v2/v3: skema v1 menandatangani tiap entri secara individual lewat
 * MANIFEST.MF + CERT.SF + CERT.RSA. Itu satu-satunya skema yang bisa dihitung tanpa
 * memfinalisasi seluruh ZIP lebih dulu, sehingga cocok untuk penandatanganan di perangkat
 * (on-device) seperti yang dilakukan aplikasi ini. Skema v2/v3 menandatangani seluruh file
 * sebagai satu blok dan butuh apksigner; kalau tetap dipakai, Android 7+ akan menolak APK
 * yang blok v2-nya tidak konsisten.
 *
 * <p>Konsekuensinya: {@code minSdkVersion} APK hasil clone tidak boleh di atas 23.
 * {@link Repackager} menurunkan minSdk otomatis bila perlu.
 */
public final class ApkSigner {

    private static final String DIGEST_ALG = "SHA-256";
    private static final String SIG_ALG = "SHA256withRSA";

    /** Panjang baris base64 pada file .SF/.MF, mengikuti konvensi jarsigner. */
    private static final int BASE64_LINE_LEN = 70;

    private ApkSigner() {
    }

    public static class SignException extends RuntimeException {
        public SignException(String msg, Throwable cause) {
            super(msg, cause);
        }

        public SignException(String msg) {
            super(msg);
        }
    }

    /** Kunci + sertifikat untuk menandatangani. */
    public static class SigningKey {
        public final PrivateKey privateKey;
        public final X509Certificate certificate;

        public SigningKey(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    /**
     * Buat kunci RSA 2048 + sertifikat self-signed, simpan sebagai PKCS#12.
     *
     * @return path keystore yang dibuat
     */
    public static File generateKeystore(File dest, String alias, String password, String cn)
            throws Exception {
        SigningKey key = generateKey(cn);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, key.privateKey, password.toCharArray(),
                new X509Certificate[] {key.certificate});
        try (FileOutputStream out = new FileOutputStream(dest)) {
            ks.store(out, password.toCharArray());
        }
        return dest;
    }

    /** Muat kunci penandatangan dari keystore PKCS#12/JKS. */
    public static SigningKey loadKeystore(File keystore, String alias, String password)
            throws Exception {
        KeyStore ks = KeyStore.getInstance(keystore.getName().endsWith(".jks") ? "JKS" : "PKCS12");
        try (InputStream in = new java.io.FileInputStream(keystore)) {
            ks.load(in, password.toCharArray());
        }
        if (!ks.isKeyEntry(alias)) {
            throw new SignException("alias '" + alias + "' tidak ada di keystore");
        }
        PrivateKey pk = (PrivateKey) ks.getKey(alias, password.toCharArray());
        java.security.cert.Certificate[] chain = ks.getCertificateChain(alias);
        if (chain == null || chain.length == 0) {
            throw new SignException("keystore tidak punya rantai sertifikat untuk alias " + alias);
        }
        return new SigningKey(pk, (X509Certificate) chain[0]);
    }

    /** Buat pasangan kunci + sertifikat self-signed baru (valid 30 tahun). */
    public static SigningKey generateKey(String cn) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048, new SecureRandom());
        KeyPair pair = kpg.generateKeyPair();

        X500Name subject = new X500Name(
                "CN=" + escapeDn(cn) + ", OU=CloneAPK, O=CloneAPK, L=Jakarta, ST=DKI, C=ID");
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 24L * 3600_000L);
        Date notAfter = new Date(now + 3650L * 3L * 24 * 3600_000L);

        java.math.BigInteger serial =
                new java.math.BigInteger(64, new SecureRandom()).setBit(63);

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
        X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject,
                org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(
                        pair.getPublic().getEncoded()));
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyCertSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                extUtils.createSubjectKeyIdentifier(pair.getPublic()));

        ContentSigner signer =
                new JcaContentSignerBuilder(SIG_ALG).build(pair.getPrivate());
        X509Certificate cert = new JcaX509CertificateConverter()
                .getCertificate(builder.build(signer));
        cert.verify(pair.getPublic());
        return new SigningKey(pair.getPrivate(), cert);
    }

    private static String escapeDn(String cn) {
        if (cn == null || cn.trim().isEmpty()) {
            return "CloneAPK";
        }
        return cn.replaceAll("[,=+<>#;\\\\\"]", "_").trim();
    }

    /**
     * Hitung digest satu entri ZIP secara streaming (tanpa menyangga isi entri).
     *
     * <p>APK nyata bisa ratusan megabyte; menahan satu entri utuh di heap akan
     * membuat perangkat kecil kehabisan memori.
     */
    private static String digestEntry(ZipFile zf, ZipEntry e, MessageDigest md)
            throws IOException {
        md.reset();
        if (!e.isDirectory()) {
            try (InputStream in = zf.getInputStream(e)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
        }
        return encodeBase64(md.digest());
    }

    /**
     * Tandatangani APK dengan skema v1+v2 memakai apksig (pustaka yang sama
     * dengan yang dipakai AGP/apksigner).
     *
     * <p>Kenapa tidak v1 saja: sejak Android 11, APK dengan targetSdk >= 30 wajib
     * punya tanda tangan skema v2; v1-only akan ditolak saat pemasangan. apksig
     * menghitung blok tanda tangan secara streaming sehingga tetap hemat memori.
     */
    public static void signApk(File unsignedZip, File dest, SigningKey key)
            throws IOException {
        try {
            com.android.apksig.ApkSigner.SignerConfig cfg =
                    new com.android.apksig.ApkSigner.SignerConfig.Builder(
                            "CLONAPK", key.privateKey,
                            java.util.Collections.singletonList(key.certificate))
                            .build();
            com.android.apksig.ApkSigner signer =
                    new com.android.apksig.ApkSigner.Builder(
                            java.util.Collections.singletonList(cfg))
                            .setInputApk(unsignedZip)
                            .setOutputApk(dest)
                            .setV1SigningEnabled(true)
                            .setV2SigningEnabled(true)
                            .build();
            signer.sign();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new SignException("penandatanganan gagal: " + e.getMessage(), e);
        }
    }

    /** Verifikasi penuh (v1+v2) dengan verifier resmi apksig. */
    public static boolean verifyApk(File apk) {
        try {
            com.android.apksig.ApkVerifier verifier =
                    new com.android.apksig.ApkVerifier.Builder(apk).build();
            return verifier.verify().isVerified();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Tandatangani ulang sebuah APK yang entri-entrinya sudah siap.
     *
     * @param entries pasangan (nama entri -> isi) dalam urutan penulisan yang diinginkan.
     *                Entri META-INF signature lama tidak boleh ada di sini.
     */
    public static byte[] sign(LinkedHashMap<String, byte[]> entries, SigningKey key) {
        try {
            List<String> names = new ArrayList<>(entries.keySet());
            MessageDigest md = MessageDigest.getInstance(DIGEST_ALG);

            StringBuilder mf = new StringBuilder();
            mf.append("Manifest-Version: 1.0\r\n");
            mf.append("Built-By: Generated-by-CloneAPK\r\n");
            mf.append("Created-By: 1.0 (CloneAPK)\r\n\r\n");

            Map<String, String> entryDigests = new LinkedHashMap<>();
            for (String name : names) {
                md.reset();
                byte[] data = entries.get(name);
                if (data != null) {
                    md.update(data);
                }
                String b64 = encodeBase64(md.digest());
                entryDigests.put(name, b64);
                mf.append("Name: ").append(name).append("\r\n");
                mf.append(DIGEST_ALG + "-Digest: ").append(b64).append("\r\n");
                mf.append("\r\n");
            }
            byte[] manifestBytes = mf.toString().getBytes("UTF-8");

            md.reset();
            md.update(manifestBytes);
            String manifestDigest = encodeBase64(md.digest());

            StringBuilder sf = new StringBuilder();
            sf.append("Signature-Version: 1.0\r\n");
            sf.append(DIGEST_ALG + "-Digest-Manifest: ").append(manifestDigest).append("\r\n");
            sf.append("Created-By: 1.0 (CloneAPK)\r\n\r\n");
            for (String name : names) {
                sf.append("Name: ").append(name).append("\r\n");
                sf.append(DIGEST_ALG + "-Digest: ").append(entryDigests.get(name)).append("\r\n");
                sf.append("\r\n");
            }
            byte[] sfBytes = sf.toString().getBytes("UTF-8");

            byte[] rsa = pkcs7Sign(sfBytes, key);

            LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
            out.put("META-INF/MANIFEST.MF", manifestBytes);
            out.put("META-INF/CERT.SF", sfBytes);
            out.put("META-INF/CERT.RSA", rsa);
            out.putAll(entries);
            return ZipWriter.write(out);
        } catch (Exception e) {
            throw new SignException("penandatanganan gagal: " + e.getMessage(), e);
        }
    }

    private static byte[] pkcs7Sign(byte[] content, SigningKey key) throws Exception {
        DigestCalculatorProvider digestProvider =
                new JcaDigestCalculatorProviderBuilder().build();
        ContentSigner contentSigner =
                new JcaContentSignerBuilder(SIG_ALG).build(key.privateKey);
        CMSSignedDataGenerator gen = new CMSSignedDataGenerator();
        gen.addSignerInfoGenerator(
                new JcaSignerInfoGeneratorBuilder(digestProvider)
                        .build(contentSigner, key.certificate));
        List<X509Certificate> chain = new ArrayList<>();
        chain.add(key.certificate);
        gen.addCertificates(new org.bouncycastle.cert.jcajce.JcaCertStore(chain));

        CMSTypedData typed = new CMSProcessableByteArray(content);
        CMSSignedData signed = gen.generate(typed, false);
        return signed.getEncoded();
    }

    /**
     * Base64 standar (RFC 4648) dengan pemotongan baris tiap {@value #BASE64_LINE_LEN}
     * karakter, mengikuti konvensi jarsigner.
     *
     * <p>Dienkode sendiri, bukan memakai {@code java.util.Base64}, karena kelas itu baru
     * ada di Android API 26 sedangkan aplikasi ini mendukung API 24. Dengan begini tidak
     * perlu desugaring dan hasil penandatanganan identik di semua versi.
     */
    public static String encodeBase64(byte[] data) {
        final String alphabet =
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder full = new StringBuilder((data.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            full.append(alphabet.charAt((n >>> 18) & 0x3F));
            full.append(alphabet.charAt((n >>> 12) & 0x3F));
            full.append(alphabet.charAt((n >>> 6) & 0x3F));
            full.append(alphabet.charAt(n & 0x3F));
            i += 3;
        }
        int rest = data.length - i;
        if (rest == 1) {
            int n = (data[i] & 0xFF) << 16;
            full.append(alphabet.charAt((n >>> 18) & 0x3F));
            full.append(alphabet.charAt((n >>> 12) & 0x3F));
            full.append("==");
        } else if (rest == 2) {
            int n = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            full.append(alphabet.charAt((n >>> 18) & 0x3F));
            full.append(alphabet.charAt((n >>> 12) & 0x3F));
            full.append(alphabet.charAt((n >>> 6) & 0x3F));
            full.append('=');
        }

        StringBuilder sb = new StringBuilder(full.length() + full.length() / BASE64_LINE_LEN * 2);
        for (int p = 0; p < full.length(); p += BASE64_LINE_LEN) {
            if (p > 0) {
                sb.append("\r\n");
            }
            sb.append(full, p, Math.min(full.length(), p + BASE64_LINE_LEN));
        }
        return sb.toString();
    }

    /**
     * Verifikasi cepat: cocokkan digest MANIFEST.MF terhadap isi ZIP yang sebenarnya.
     *
     * <p>SHA-256 wajib ada di setiap JVM/Android, jadi kegagalan mencarinya dibungkus
     * menjadi {@link SignException} agar pemanggil tidak perlu menangani kasus mustahil.
     */
    public static boolean verifyV1(byte[] apk) throws IOException {
        TmpFile tmp = new TmpFile(apk);
        try {
            return verifyV1(tmp.file());
        } finally {
            tmp.close();
        }
    }

    /** Verifikasi streaming langsung dari berkas, memori konstan. */
    public static boolean verifyV1(File apk) throws IOException {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance(DIGEST_ALG);
        } catch (NoSuchAlgorithmException e) {
            throw new SignException(DIGEST_ALG + " tidak tersedia di runtime ini", e);
        }
        try (ZipFile zf = new ZipFile(apk)) {
            ZipEntry mfEntry = zf.getEntry("META-INF/MANIFEST.MF");
            if (mfEntry == null) {
                return false;
            }
            String mf = new String(read(zf, mfEntry), "UTF-8");
            String currentName = null;
            for (String line : mf.split("\r\n")) {
                if (line.startsWith("Name: ")) {
                    currentName = line.substring(6).trim();
                } else if (line.startsWith("SHA-256-Digest: ") && currentName != null) {
                    String expected = line.substring(16).trim();
                    ZipEntry e = zf.getEntry(currentName);
                    if (e == null) {
                        return false;
                    }
                    if (!digestEntry(zf, e, md).equals(expected)) {
                        return false;
                    }
                    currentName = null;
                }
            }
            return hasSignaturePair(zf);
        }
    }

    /**
     * Pasangan berkas tanda tangan v1. Nama berkas mengikuti nama signer
     * (jarsigner memakai CERT, apksig memakai nama yang kita berikan),
     * jadi yang diperiksa adalah ekstensinya, bukan namanya.
     */
    private static boolean hasSignaturePair(ZipFile zf) {
        boolean sf = false;
        boolean key = false;
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName().toUpperCase();
            if (!n.startsWith("META-INF/")) {
                continue;
            }
            if (n.endsWith(".SF")) {
                sf = true;
            } else if (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC")) {
                key = true;
            }
        }
        return sf && key;
    }

    static byte[] read(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** File sementara untuk memverifikasi hasil, dihapus otomatis. */
    static final class TmpFile implements AutoCloseable {
        private final File f;

        TmpFile(byte[] data) throws IOException {
            f = File.createTempFile("clonapk-verify", ".apk");
            f.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
        }

        File file() {
            return f;
        }

        @Override
        public void close() {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
