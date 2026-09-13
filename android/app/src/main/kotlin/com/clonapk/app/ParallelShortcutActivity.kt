package com.clonapk.app

import android.app.Activity
import android.os.Bundle
import top.niunaijun.blackbox.BlackBoxCore

/**
 * Trampolin untuk ikon layar utama (shortcut) aplikasi di dalam mesin
 * parallel. Launcher membuka activity transparan ini; ia meneruskan ke
 * BlackBox untuk menjalankan aplikasi guest, lalu menutup diri.
 */
class ParallelShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent?.getStringExtra("package")
        if (pkg != null && pkg.isNotEmpty()) {
            try {
                val core = BlackBoxCore.get()
                if (core.isInstalled(pkg, 0)) {
                    core.launchApk(pkg, 0)
                }
            } catch (_: Throwable) {
                // Ikon hanya pelengkap; kegagalan tidak boleh menampilkan crash.
            }
        }
        finish()
    }
}
