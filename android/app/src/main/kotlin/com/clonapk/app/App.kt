package com.clonapk.app

import android.app.Application
import android.content.Context
import top.niunaijun.blackbox.BlackBoxCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration

/**
 * Application host. Satu-satunya tugas tambahannya: memasang mesin virtual
 * BlackBox (gaya Parallel Space) sedini mungkin, seperti yang disyaratkan
 * engine-nya.
 *
 * Setiap langkah dibungkus try/catch: kalau mesin gagal terpasang di suatu
 * perangkat, ClonApk harus tetap hidup — ruang virtual & mode APK tidak
 * bergantung pada mesin ini.
 */
class App : Application() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        try {
            BlackBoxCore.get().closeCodeInit()
        } catch (_: Throwable) {
        }
        try {
            BlackBoxCore.get().onBeforeMainApplicationAttach(this, base)
        } catch (_: Throwable) {
        }
        try {
            BlackBoxCore.get().doAttachBaseContext(
                base,
                object : ClientConfiguration() {
                    override fun getHostPackageName(): String = packageName
                }
            )
        } catch (_: Throwable) {
        }
        try {
            BlackBoxCore.get().onAfterMainApplicationAttach(this, base)
        } catch (_: Throwable) {
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            BlackBoxCore.get().doCreate()
        } catch (_: Throwable) {
        }
    }
}
