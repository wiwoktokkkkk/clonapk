import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import '../models/app_info.dart';
import '../models/clone_result.dart';

/// Satu-satunya titik yang berbicara dengan sisi Android.
///
/// Semua metode melempar [CloneFailure] dengan pesan berbahasa Indonesia yang layak
/// ditampilkan ke pengguna, supaya layar tidak perlu menangani PlatformException.
class CloneService {
  CloneService._();

  static final CloneService instance = CloneService._();

  static const MethodChannel _channel = MethodChannel('com.clonapk.app/clone');
  static const EventChannel _progressChannel =
      EventChannel('com.clonapk.app/clone_progress');

  final StreamController<CloneProgress> _progress =
      StreamController<CloneProgress>.broadcast();
  StreamSubscription<dynamic>? _progressSub;

  /// Aliran kemajuan proses clone. Berlangganan sebelum memanggil [clone].
  Stream<CloneProgress> get progress {
    _progressSub ??= _progressChannel.receiveBroadcastStream().listen(
      (event) {
        if (event is Map) {
          _progress.add(CloneProgress.fromMap(event));
        }
      },
      onError: (_) {/* aliran kemajuan bersifat opsional */},
    );
    return _progress.stream;
  }

  Future<List<AppInfo>> getInstalledApps({bool includeSystem = false}) async {
    try {
      final res = await _channel.invokeMethod<List<dynamic>>(
        'getInstalledApps',
        {'includeSystem': includeSystem},
      );
      final list = (res ?? const <dynamic>[])
          .whereType<Map<dynamic, dynamic>>()
          .map(AppInfo.fromMap)
          .toList();
      return list;
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Gagal membaca daftar aplikasi.');
    }
  }

  Future<ApkInspection> inspectApk(String path) async {
    try {
      final res = await _channel
          .invokeMethod<Map<dynamic, dynamic>>('inspectApk', {'path': path});
      if (res == null) {
        throw const CloneFailure('Berkas APK tidak bisa dibaca.');
      }
      return ApkInspection.fromMap(res);
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Berkas APK tidak bisa dibaca.');
    }
  }

  Future<CloneResult> clone({
    required String sourcePath,
    required String newPackage,
    String? newLabel,
    bool deepScan = false,
    List<String> splits = const [],
  }) async {
    try {
      final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('clone', {
        'sourcePath': sourcePath,
        'newPackage': newPackage,
        'newLabel': newLabel,
        'deepScan': deepScan,
        'splits': splits,
      });
      if (res == null) {
        throw const CloneFailure('Proses clone tidak mengembalikan hasil.');
      }
      return CloneResult.fromMap(res);
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Proses clone gagal.');
    }
  }

  Future<List<HistoryItem>> getHistory() async {
    try {
      final res = await _channel.invokeMethod<List<dynamic>>('getHistory');
      return (res ?? const <dynamic>[])
          .whereType<Map<dynamic, dynamic>>()
          .map(HistoryItem.fromMap)
          .toList();
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Gagal membaca riwayat.');
    }
  }

  Future<void> installApk(String path, {List<String> extraPaths = const []}) async {
    try {
      await _channel.invokeMethod<bool>(
          'installApk', {'path': path, 'extraPaths': extraPaths});
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Gagal membuka pemasang.');
    }
  }

  Future<void> shareApk(String path) async {
    // share_plus menangani sendiri berkas lokal dan lebih andal lintas versi
    // Android daripada Intent manual.
    final f = File(path);
    if (!await f.exists()) {
      throw const CloneFailure('Berkas hasil clone sudah tidak ada.');
    }
    await SharePlus.instance.share(
      ShareParams(files: [XFile(path)], subject: f.uri.pathSegments.last),
    );
  }

  Future<bool> deleteApk(String path) async {
    try {
      final ok =
          await _channel.invokeMethod<bool>('deleteApk', {'path': path}) ?? false;
      return ok;
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Gagal menghapus berkas.');
    }
  }

  /// Package clone otomatis berikutnya yang belum terpasang
  /// (mis. com.foo.bar -> com.foo.bar.clone -> com.foo.bar.clone2).
  Future<String> nextClonePackage(String original) async {
    try {
      final p = await _channel
          .invokeMethod<String>('nextClonePackage', {'original': original});
      if (p == null || p.isEmpty) {
        throw const CloneFailure('Gagal membuat nama package otomatis.');
      }
      return p;
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Gagal membuat nama package otomatis.');
    }
  }

  /// Buka aplikasi yang sudah terpasang (dipakai untuk membuka clone).
  Future<bool> launchApp(String packageName) async {
    try {
      return await _channel
              .invokeMethod<bool>('launchApp', {'package': packageName}) ??
          false;
    } on PlatformException {
      return false;
    }
  }

  /// Minta sistem mencopot pemasangan aplikasi.
  Future<bool> uninstallApp(String packageName) async {
    try {
      return await _channel
              .invokeMethod<bool>('uninstallApp', {'package': packageName}) ??
          false;
    } on PlatformException {
      return false;
    }
  }

  Future<bool> isInstalled(String packageName) async {
    try {
      return await _channel
              .invokeMethod<bool>('isInstalled', {'package': packageName}) ??
          false;
    } on PlatformException {
      return false;
    }
  }

  Future<String> exportDir() async {
    try {
      final p = await _channel.invokeMethod<String>('exportDir');
      return p ?? '';
    } on PlatformException {
      return '';
    }
  }

  /// Buka pemilih berkas untuk mengambil APK dari penyimpanan.
  ///
  /// Pemilih memakai Storage Access Framework, jadi tidak butuh izin
  /// penyimpanan khusus sejak Android 11.
  ///
  /// @return path APK terpilih, atau null kalau pengguna membatalkan.
  Future<String?> pickApkFile() async {
    // API file_picker 12.x: metode statis yang mengembalikan List<PlatformFile>.
    final files = await FilePicker.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['apk', 'apks', 'xapk'],
    );
    final path = files.isEmpty ? null : files.single.path;
    if (path == null || path.isEmpty) return null;
    return path;
  }

  // ------------------------------------------------------------ ruang virtual

  /// Status ruang virtual: {profileOwner, hasProfile}.
  Future<Map<String, bool>> virtualStatus() async {
    final m = await _channel
        .invokeMethod<Map<dynamic, dynamic>>('virtualStatus');
    return {
      'profileOwner': (m?['profileOwner'] ?? false) as bool,
      'hasProfile': (m?['hasProfile'] ?? false) as bool,
    };
  }

  /// Buka layar persetujuan profil kerja milik sistem.
  Future<bool> provisionProfile() =>
      _channel.invokeMethod<bool>('provisionProfile').then((v) => v ?? false);

  /// Pasang instance kedua (package sama) ke ruang virtual.
  Future<Map<String, dynamic>> cloneVirtual(String packageName) async {
    try {
      final m = await _channel.invokeMethod<Map<dynamic, dynamic>>(
          'cloneVirtual', {'package': packageName});
      return {
        'newPackage': (m?['newPackage'] ?? packageName) as String,
        'type': (m?['type'] ?? 'virtual') as String,
      };
    } on PlatformException catch (e) {
      throw CloneFailure(
          e.message ?? 'Ruang virtual menolak pemasangan aplikasi ini.');
    }
  }

  /// Jalankan aplikasi di dalam ruang virtual.
  Future<bool> launchVirtual(String packageName) => _channel
      .invokeMethod<bool>('launchVirtual', {'package': packageName})
      .then((v) => v ?? false);

  /// Apakah aplikasi sudah punya instance di ruang virtual?
  Future<bool> virtualHas(String packageName) => _channel
      .invokeMethod<bool>('virtualHas', {'package': packageName})
      .then((v) => v ?? false);

  /// Buka clone apa pun sesuai jenisnya.
  Future<bool> openClone(HistoryItem item) => item.isVirtual
      ? launchVirtual(item.originalPackage)
      : launchApp(item.newPackage);

  // ------------------------------------------------------- mesin parallel

  /// Daftar aplikasi di dalam mesin parallel (BlackBox).
  Future<List<AppInfo>> parallelApps() async {
    try {
      final res = await _channel.invokeMethod<List<dynamic>>('parallelApps');
      return (res ?? const <dynamic>[])
          .whereType<Map<dynamic, dynamic>>()
          .map((m) => AppInfo.fromMap({
                'packageName': m['packageName'],
                'label': m['label'],
                'versionName': m['versionName'],
                'apkPath': '',
                'iconPath': m['iconPath'],
              }))
          .toList();
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Mesin parallel tidak tersedia.');
    }
  }

  /// Masukkan aplikasi ke mesin parallel.
  Future<void> parallelInstall(String packageName) async {
    try {
      await _channel
          .invokeMethod<Map<dynamic, dynamic>>('parallelInstall', {'package': packageName});
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Mesin parallel menolak aplikasi ini.');
    }
  }

  /// Jalankan aplikasi di dalam mesin parallel.
  Future<bool> parallelLaunch(String packageName) => _channel
      .invokeMethod<bool>('parallelLaunch', {'package': packageName})
      .then((v) => v ?? false);

  /// Keluarkan aplikasi dari mesin parallel.
  Future<bool> parallelUninstall(String packageName) => _channel
      .invokeMethod<bool>('parallelUninstall', {'package': packageName})
      .then((v) => v ?? false);
}

/// Kegagalan yang pesannya aman ditampilkan langsung ke pengguna.
class CloneFailure implements Exception {
  const CloneFailure(this.message);

  final String message;

  @override
  String toString() => message;
}
