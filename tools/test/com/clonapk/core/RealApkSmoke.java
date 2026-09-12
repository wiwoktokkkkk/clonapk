package com.clonapk.core;

import java.io.File;

/**
 * Uji asap: clone APK sungguhan (hasil aapt2/AGP, bukan buatan factory uji)
 * lalu verifikasi hasilnya dengan verifier bawaan.
 */
public final class RealApkSmoke {
    public static void main(String[] a) throws Exception {
        File src = new File(a[0]);
        File outDir = new File(a[1]);
        String newPkg = a.length > 2 ? a[2] : "com.clonapk.app.smoke";
        System.out.println("sumber: " + src + " (" + src.length() + " byte)");
        ApkSigner.SigningKey key = ApkSigner.generateKey("Smoke");
        Repackager.Result r = Repackager.clone(
                src, newPkg, outDir, "smoke-clone.apk", key, false,
                (s, p) -> System.out.printf("  %3d%% %s%n", p, s));
        System.out.println("clone OK -> " + r.outputFile + " (" + r.outputFile.length() + " byte)");
        System.out.println("package asli : " + r.originalPackage);
        System.out.println("package baru : " + r.newPackage);
        System.out.println("minSdk turun : " + r.minSdkLowered);
        boolean v1 = ApkSigner.verifyApk(r.outputFile);
        System.out.println("verifikasi apksig (v1+v2): " + v1);
        if (!v1) {
            System.exit(1);
        }
    }
}
