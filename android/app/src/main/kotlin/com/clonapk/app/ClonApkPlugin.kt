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
            "installApk" -> result.success(installApk(call))
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

        emit("Menyiapkan", 2)
        val outDir = exportDir()
        val name = ApkUtil.safeFileName(newLabel ?: newPackage.substringAfterLast('.'), newPackage)
        val res = Repackager.clone(source, newPackage, outDir, name, signingKey, deepScan) { stage, pct ->
            emit(stage, pct)
        }

        val out = res.outputFile
        return mapOf(
            "path" to out.absolutePath,
            "fileName" to out.name,
            "newPackage" to res.newPackage,
            "originalPackage" to res.originalPackage,
            "sizeBytes" to out.length(),
            "minSdkLowered" to res.minSdkLowered
        )
    }

    // ---------------------------------------------------------------- utilitas

    private fun listHistory(): Any {
        val dir = exportDir()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk") } ?: return emptyList<Any>()
        return files.sortedByDescending { it.lastModified() }.map {
            mapOf(
                "path" to it.absolutePath,
                "fileName" to it.name,
                "sizeBytes" to it.length(),
                "modifiedAt" to it.lastModified()
            )
        }
    }

    private fun installApk(call: MethodCall): Boolean {
        val f = fileFrom(call)
        val uri = uriFor(f)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
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
        return f.delete()
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

    companion object {
        private const val CHANNEL = "com.clonapk.app/clone"
        private const val PROGRESS_CHANNEL = "com.clonapk.app/clone_progress"
    }
}
