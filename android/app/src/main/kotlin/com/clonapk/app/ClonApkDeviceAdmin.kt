package com.clonapk.app

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * Penerima admin perangkat untuk penyediaan profil kerja (ruang virtual).
 *
 * Saat pengguna menyetujui layar "Siapkan profil kerja" milik sistem, komponen
 * ini otomatis menjadi *profile owner* — posisi yang memberi ClonApk wewenang
 * memasang instance kedua sebuah aplikasi (package sama, tanpa modifikasi APK)
 * ke dalam profil tersebut.
 */
class ClonApkDeviceAdmin : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // Tidak ada langkah tambahan: status profile owner sudah aktif sejak
        // callback ini dipanggil.
    }
}
