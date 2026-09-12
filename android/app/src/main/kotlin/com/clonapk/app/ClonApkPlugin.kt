package com.clonapk.app

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.clonapk.core.ApkSigner
import com.clonapk.core.ApkUtil
import com.clonapk.core.Repackager
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Jembatan Flutter <-> mesin clone.
 *
 * Semua kerja berat (baca APK, tambal manifest, tanda tangan) jalan di thread latar
 * supaya UI tidak pernah macet; kemajuan dikirim lewat EventChannel.
 */
class ClonApkPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {

    private lateinit var channel: MethodChannel
    private lateinit var progressChannel: EventChannel
    private lateinit var context: Context
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val progressSink = AtomicReference<EventChannel.EventSink?>(null)

    private val signingKey: ApkSigner.SigningKey by lazy { loadOrCreateKey() }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel.setMethodCallHandler(this)
        progressChannel = EventChannel(binding.binaryMessenger, PROGRESS_CHANNEL)
        progressChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(args: Any?, sink: EventChannel.EventSink?) {
                progressSink.set(sink)
            }

            override fun onCancel(args: Any?) {
                progressSink.set(null)
            }
        })
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        progressChannel.setStreamHandler(null)
        executor.shutdownNow()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "getInstalledApps" -> onWorker(result) { getInstalledApps(call) }
            "inspectApk" -> onWorker(result) { inspectApk(call) }
            "clone" -> onWorker(result) { clone(call) }
            "getHistory" -> onWorker(result) { listHistory() }
            "nextClonePackage" -> onWorker(result) { nextClonePackage(call) }
            "launchApp" -> result.success(launchApp(call))
            "uninstallApp" -> result.success(uninstallApp(call))
            "isInstalled" -> result.success(isInstalled(call))
            "installApk" -> result.success(installApk(call))
            "virtualStatus" -> onWorker(result) { virtualStatus() }
            "provisionProfile" -> result.success(provisionProfile())
            "cloneVirtual" -> onWorker(result) { cloneVirtual(call) }
            "launchVirtual" -> result.success(launchVirtual(call))
            "shareApk" -> result.success(shareApk(call))
            "deleteApk" -> result.success(deleteApk(call))
            "revealApk" -> result.success(revealApk(call))
            "exportDir" -> result.success(exportDir().absolutePath)
            else -> result.notImplemented()
        }
    }

    // ----------------------------------------------------------------- pekerja

    private fun onWorker(result: MethodChannel.Result, block: () -> Any?) {
        executor.execute {
            try {
                val value = block()
                mainHandler.post { result.success(value) }
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                mainHandler.post { result.error("clone_failed", msg, null) }
            }
        }
    }

    private fun emit(stage: String, percent: Int) {
        val sink = progressSink.get() ?: return
        mainHandler.post {
            sink.success(mapOf("stage" to stage, "percent" to percent))
        }
    }

    /**
     * Daftar aplikasi yang terpasang dan bisa diluncurkan.
     *
     * Perlu {@code <queries>} di manifest: sejak Android 11 visibilitas paket dibatasi,
     * tanpa deklarasi itu daftar akan tampak kosong.
     */
    private fun getInstalledApps(call: MethodCall): Any {
        val includeSystem = call.argument<Boolean>("includeSystem") ?: false
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolvable = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName }
            .toSet()

        val packages = pm.getInstalledPackages(0)
        val out = ArrayList<Map<String, Any?>>()
        for (pi in packages) {
            val pkg = pi.packageName
            val app = pi.applicationInfo ?: continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) continue
            if (!resolvable.contains(pkg)) continue

            out.add(
                mapOf(
                    "packageName" to pkg,
                    "label" to app.loadLabel(pm).toString(),
                    "versionName" to (versionNameOf(pi) ?: "-"),
                    "isSystem" to isSystem,
                    "apkPath" to (app.sourceDir ?: ""),
                    "splitPaths" to (app.splitSourceDirs?.toList() ?: emptyList<String>()),
                    "sizeBytes" to sizeOf(app.sourceDir),
                    "iconPath" to iconPathFor(pkg, app.loadIcon(pm))
                )
            )
        }
        out.sortBy { (it["label"] as String).lowercase() }
        return out
    }

    private fun inspectApk(call: MethodCall): Any {
        val path = call.argument<String>("path") ?: error("path wajib diisi")
        val info = Repackager.inspect(File(path))
        return mapOf(
            "packageName" to info.packageName,
            "versionName" to (info.versionName ?: "-"),
            "sizeBytes" to info.sizeBytes,
            "permissions" to info.permissions,
            "suggestedPackage" to ApkUtil.suggestPackageName(info.packageName, "clone")
        )
    }

    private fun clone(call: MethodCall): Any {
        val source = File(call.argument<String>("sourcePath") ?: error("sourcePath wajib diisi"))
        val newPackage = call.argument<String>("newPackage") ?: error("newPackage wajib diisi")
        val newLabel = call.argument<String>("newLabel")
        val deepScan = call.argument<Boolean>("deepScan") ?: false
        // Aplikasi modern terpasang sebagai beberapa berkas (base + split untuk
        // ABI/kepadatan layar/bahasa). Semua bagian wajib ikut di-clone dan
        // dipasang bersama-sama, kalau tidak installer menolak ("tidak
        // kompatibel") karena pustaka native hilang.
        val splits = call.argument<List<String>>("splits") ?: emptyList()

        emit("Menyiapkan", 2)
        val outDir = exportDir()
        val name = ApkUtil.safeFileName(newLabel ?: newPackage.substringAfterLast('.'), newPackage)
        val spanBase = 60.0 / (1 + splits.size)
        var done = 0
        val res = Repackager.clone(source, newPackage, outDir, name, signingKey, deepScan) { stage, pct ->
            emit(stage, (done * spanBase + pct * spanBase / 100.0).toInt())
        }
        done++

        val extras = ArrayList<String>()
        for ((idx, sp) in splits.withIndex()) {
            val splitFile = File(sp)
            if (!splitFile.isFile) continue
            val splitRes = Repackager.clone(
                splitFile, newPackage, outDir,
                "split-${idx + 1}.apk", signingKey, deepScan
            ) { _, _ -> }
            extras.add(splitRes.outputFile.absolutePath)
            done++
            emit("Menggandakan bagian ${idx + 1}/${splits.size}", (done * spanBase).toInt())
        }

        val out = res.outputFile
        recordHistory(
            res.originalPackage, res.newPackage, newLabel ?: "", out,
            extras = extras
        )
        return mapOf(
            "path" to out.absolutePath,
            "fileName" to out.name,
            "newPackage" to res.newPackage,
            "originalPackage" to res.originalPackage,
            "sizeBytes" to out.length(),
            "minSdkLowered" to res.minSdkLowered,
            "extraPaths" to extras
        )
    }

    // ---------------------------------------------------------------- utilitas

    /**
     * Riwayat clone disimpan sebagai JSON kecil di samping folder keluaran,
     * supaya pasangan "asli -> clone" tetap known tanpa menebak-nebak nama.
     */
    private fun historyFile(): File = File(exportDir(), "history.json")

    private fun readHistoryArray(): org.json.JSONArray {
        val f = historyFile()
        if (!f.isFile) return org.json.JSONArray()
        return try {
            org.json.JSONArray(f.readText())
        } catch (t: Throwable) {
            org.json.JSONArray()
        }
    }

    private fun recordHistory(
        original: String,
        newPkg: String,
        label: String,
        out: File,
        type: String = "apk",
        extras: List<String> = emptyList()
    ) {
        val rec = org.json.JSONObject()
        rec.put("originalPackage", original)
        rec.put("newPackage", newPkg)
        rec.put("label", label)
        rec.put("fileName", out.name)
        rec.put("path", out.absolutePath)
        rec.put("at", System.currentTimeMillis())
        rec.put("type", type)
        rec.put("extraPaths", org.json.JSONArray(extras))
        val fresh = org.json.JSONArray()
        fresh.put(rec)
        val old = readHistoryArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optString("newPackage") != newPkg) fresh.put(o)
        }
        try {
            historyFile().writeText(fresh.toString())
        } catch (t: Throwable) {
            // riwayat bersifat pelengkap; kegagalan menulis tidak menggagalkan clone
        }
    }

    private fun removeFromHistory(path: String) {
        val old = readHistoryArray()
        val kept = org.json.JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optString("path") != path) kept.put(o)
        }
        try {
            historyFile().writeText(kept.toString())
        } catch (t: Throwable) {
        }
    }

    private fun listHistory(): Any {
        val arr = readHistoryArray()
        val pm = context.packageManager
        val outList = ArrayList<Map<String, Any?>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val path = o.optString("path", "")
            val f = File(path)
            if (!f.isFile) continue
            val pkg = o.optString("newPackage", "")
            outList.add(
                mapOf(
                    "path" to path,
                    "fileName" to o.optString("fileName", f.name),
                    "sizeBytes" to f.length(),
                    "modifiedAt" to o.optLong("at", f.lastModified()),
                    "label" to o.optString("label", ""),
                    "originalPackage" to o.optString("originalPackage", ""),
                    "newPackage" to pkg,
                    "type" to o.optString("type", "apk"),
                    "extraPaths" to extrasOf(o),
                    "installed" to
                            if (o.optString("type", "apk") == "virtual")
                                virtualInstalled(pkg)
                            else
                                isPkgInstalled(pkg)
                )
            )
        }
        return outList
    }

    /**
     * Usulkan package clone berikutnya yang belum terpasang:
     * com.foo.bar -> com.foo.bar.clone -> com.foo.bar.clone2 -> ...
     * Pengguna tidak perlu mengetik apa pun; identitas dibuat otomatis.
     */
    private fun nextClonePackage(call: MethodCall): Any {
        val base = call.argument<String>("original") ?: error("original wajib diisi")
        var i = 1
        while (true) {
            val candidate = if (i == 1) "$base.clone" else "$base.clone$i"
            if (!isPkgInstalled(candidate)) return candidate
            i++
        }
    }

    private fun isPkgInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (t: Throwable) {
        false
    }

    private fun extrasOf(o: org.json.JSONObject): List<String> {
        val arr = o.optJSONArray("extraPaths") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            out.add(arr.getString(i))
        }
        return out
    }

    /** Instance virtual dianggap terpasang bila profil terkelola memuat activity-nya. */
    private fun virtualInstalled(pkg: String): Boolean = try {
        val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val user = um.userProfiles.firstOrNull {
            it != android.os.Process.myUserHandle()
        }
        if (user == null) {
            false
        } else {
            val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                    as android.content.pm.LauncherApps
            val acts = la.getActivityList(pkg, user)
            !acts.isNullOrEmpty()
        }
    } catch (t: Throwable) {
        false
    }

    /** Buka aplikasi (clone) yang sudah terpasang, seperti membuka app biasa. */
    private fun launchApp(call: MethodCall): Boolean {
        val pkg = call.argument<String>("package") ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Minta sistem mencopot pemasangan aplikasi clone. */
    private fun uninstallApp(call: MethodCall): Boolean {
        val pkg = call.argument<String>("package") ?: return false
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun isInstalled(call: MethodCall): Boolean {
        val pkg = call.argument<String>("package") ?: return false
        return isPkgInstalled(pkg)
    }

    private fun installApk(call: MethodCall): Boolean {
        val f = fileFrom(call)
        val extras = call.argument<List<String>>("extraPaths") ?: emptyList()
        if (extras.isNotEmpty()) {
            // Multi-berkas (base + split): wajib satu sesi PackageInstaller,
            // bukan ACTION_VIEW — installer biasa hanya menerima satu APK.
            return installSession(f, extras)
        }
        val uri = uriFor(f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return true
    }

    /** Pasang base + split sekaligus lewat satu sesi PackageInstaller. */
    private fun installSession(main: File, extras: List<String>): Boolean {
        val files = ArrayList<File>()
        files.add(main)
        for (p in extras) {
            val f = File(p)
            if (f.isFile) files.add(f)
        }
        val pi = context.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(
            android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        var total = 0L
        for (f in files) total += f.length()
        params.setSize(total)
        val sessionId = pi.createSession(params)
        pi.openSession(sessionId).use { session ->
            for (f in files) {
                session.openWrite(f.name, 0, f.length()).use { out ->
                    f.inputStream().use { input -> input.copyTo(out) }
                }
            }
            val done = Intent(context, ClonApkInstallReceiver::class.java)
                .setAction("com.clonapk.app.INSTALL_DONE")
            val pending = android.app.PendingIntent.getBroadcast(
                context,
                sessionId,
                done,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_MUTABLE
            )
            session.commit(pending.intentSender)
        }
        return true
    }

    private fun shareApk(call: MethodCall): Boolean {
        val f = fileFrom(call)
        val uri = uriFor(f)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, f.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Bagikan APK").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        return true
    }

    private fun deleteApk(call: MethodCall): Boolean {
        val f = fileFrom(call)
        val ok = f.delete()
        if (ok) removeFromHistory(f.absolutePath)
        return ok
    }

    private fun revealApk(call: MethodCall): Boolean {
        val f = fileFrom(call)
        val uri = uriFor(f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * APK hasil disimpan di direktori berkas publik aplikasi supaya bisa dibagikan
     * dan tetap ada setelah aplikasi ditutup.
     */
    private fun exportDir(): File {
        // getExternalFilesDir tetap tersedia setelah aplikasi ditutup dan bisa dibagikan
        // lewat FileProvider. Penyimpanan publik bersama tidak bisa diakses lagi sejak
        // Android 11 tanpa izin khusus yang tidak layak diminta untuk kasus ini.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "clones")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun fileFrom(call: MethodCall): File {
        val p = call.argument<String>("path") ?: error("path wajib diisi")
        val f = File(p)
        if (!f.isFile) error("Berkas tidak ditemukan: ${f.name}")
        return f
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", f
    )

    private fun versionNameOf(pi: PackageInfo): String? = pi.versionName

    private fun sizeOf(path: String?): Long =
        try {
            if (path == null) 0L else File(path).length()
        } catch (t: Throwable) {
            0L
        }

    /**
     * Simpan ikon aplikasi sebagai PNG supaya bisa dikirim ke Flutter sebagai path.
     * Flutter tidak bisa membaca Drawable Android secara langsung.
     */
    private fun iconPathFor(pkg: String, icon: Drawable): String? {
        return try {
            val dir = File(context.cacheDir, "icons")
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, "$pkg.png")
            if (out.isFile) return out.absolutePath
            val bmp = drawableToBitmap(icon)
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.absolutePath
        } catch (t: Throwable) {
            null
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap {
        if (d is BitmapDrawable && d.bitmap != null) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 96
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 96
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, c.width, c.height)
        d.draw(c)
        return bmp
    }

    /**
     * Kunci penandatangan disimpan di direktori privat aplikasi.
     *
     * Penting: kunci dibuat sekali lalu dipakai terus. Kalau kunci berubah tiap clone,
     * pengguna tidak bisa menimpa pemasangan clone sebelumnya.
     */
    private fun loadOrCreateKey(): ApkSigner.SigningKey {
        val dir = File(context.filesDir, "keys")
        if (!dir.exists()) dir.mkdirs()
        val ks = File(dir, "clone.p12")
        val pwFile = File(dir, "clone.pw")
        val alias = "clonapk"
        return try {
            val password = if (pwFile.isFile) {
                pwFile.readText().trim()
            } else {
                // Acak sekali lalu disimpan. Jangan diturunkan dari identitas perangkat:
                // kalau diturunkan, kunci jadi tak terbaca setelah pindah perangkat atau
                // setelah cadangan dipulihkan, dan clone lama tak bisa ditimpa.
                val generated = "clonapk-" + java.util.UUID.randomUUID().toString()
                pwFile.writeText(generated)
                generated
            }
            if (ks.isFile) {
                ApkSigner.loadKeystore(ks, alias, password)
            } else {
                ApkSigner.generateKeystore(ks, alias, password, "ClonApk")
                ApkSigner.loadKeystore(ks, alias, password)
            }
        } catch (t: Throwable) {
            // Kunci tetap dibuat di memori supaya sesi ini masih bisa dipakai.
            ApkSigner.generateKey("ClonApk")
        }
    }

    // ------------------------------------------------------------ ruang virtual

    private fun deviceAdminComponent() =
            android.content.ComponentName(context, ClonApkDeviceAdmin::class.java)

    /**
     * Cek status profile owner secara aman: isProfileOwner tidak lagi ada di
     * stub SDK API 36 (jadi API sistem), jadi dipanggil lewat refleksi dan
     * cadangannya isAdminActive yang tetap publik.
     */
    private fun isProfileOwnerSafe(): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
        val comp = deviceAdminComponent()
        return try {
            val m = dpm.javaClass.getMethod(
                    "isProfileOwner", android.content.ComponentName::class.java)
            m.invoke(dpm, comp) == true
        } catch (t: Throwable) {
            try {
                dpm.isAdminActive(comp)
            } catch (t2: Throwable) {
                false
            }
        }
    }

    /** Status ruang virtual: apakah ClonApk profile owner + ada profil terkelola. */
    private fun virtualStatus(): Any {
        val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val managed = um.userProfiles.firstOrNull {
            it != android.os.Process.myUserHandle()
        }
        return mapOf(
            "profileOwner" to isProfileOwnerSafe(),
            "hasProfile" to (managed != null)
        )
    }

    /**
     * Buka layar penyediaan profil kerja milik sistem. Persetujuan dilakukan
     * pengguna lewat dialog sistem; setelah itu ClonApk menjadi profile owner.
     */
    private fun provisionProfile(): Boolean {
        val intent = android.content.Intent(
                android.app.admin.DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        intent.putExtra(
                android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                deviceAdminComponent())
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Pasang instance kedua sebuah aplikasi ke ruang virtual: package SAMA,
     * tanpa memodifikasi atau memasang ulang APK apa pun.
     */
    private fun cloneVirtual(call: MethodCall): Any {
        val pkg = call.argument<String>("package") ?: error("package wajib diisi")
        if (!isProfileOwnerSafe()) {
            error("Ruang virtual belum aktif. Aktifkan dulu dari beranda.")
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as android.app.admin.DevicePolicyManager
        // Signature API ini berbeda antar versi Android; terima Boolean/Int.
        val res: Any? = dpm.installExistingPackage(deviceAdminComponent(), pkg)
        val ok = when (res) {
            is Boolean -> res
            is Int -> res == 1
            else -> true
        }
        if (!ok) {
            error("Sistem menolak memasang $pkg ke ruang virtual.")
        }
        recordHistory(pkg, "$pkg.virtual", "", java.io.File(""), "virtual")
        return mapOf("newPackage" to pkg, "type" to "virtual")
    }

    /** Jalankan aplikasi di dalam ruang virtual lewat LauncherApps. */
    private fun launchVirtual(call: MethodCall): Boolean {
        val pkg = call.argument<String>("package") ?: return false
        val um = context.getSystemService(Context.USER_SERVICE) as android.os.UserManager
        val user = um.userProfiles.firstOrNull {
            it != android.os.Process.myUserHandle()
        } ?: return false
        val la = context.getSystemService(Context.LAUNCHER_APPS_SERVICE)
                as android.content.pm.LauncherApps
        return try {
            val acts = la.getActivityList(pkg, user)
            if (acts.isNullOrEmpty()) {
                return false
            }
            la.startMainActivity(acts[0].componentName, user, null, null)
            true
        } catch (t: Throwable) {
            false
        }
    }

    companion object {
        private const val CHANNEL = "com.clonapk.app/clone"
        private const val PROGRESS_CHANNEL = "com.clonapk.app/clone_progress"
    }
}
