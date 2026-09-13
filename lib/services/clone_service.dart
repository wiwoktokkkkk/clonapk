import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import '../models/app_info.dart';
import '../models/clone_result.dart';
import '../models/parallel_app.dart';

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

  /// Daftar instance aplikasi di dalam mesin parallel (BlackBox).
  Future<List<ParallelApp>> parallelApps() async {
    try {
      final res = await _channel.invokeMethod<List<dynamic>>('parallelApps');
      return (res ?? const <dynamic>[])
          .whereType<Map<dynamic, dynamic>>()
          .map(ParallelApp.fromMap)
          .toList();
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Mesin parallel tidak tersedia.');
    }
  }

  /// Masukkan aplikasi ke mesin parallel; selalu membuat instance baru
  /// (WA 1, WA 2, ...) dan mengembalikan {packageName, userId}.
  Future<Map<String, dynamic>> parallelInstall(String packageName) async {
    try {
      final m = await _channel.invokeMethod<Map<dynamic, dynamic>>(
          'parallelInstall', {'package': packageName});
      return {
        'packageName': (m?['packageName'] ?? packageName) as String,
        'userId': (m?['userId'] ?? 0) as int,
      };
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Mesin parallel menolak aplikasi ini.');
    }
  }

  /// Jalankan satu instance di dalam mesin parallel.
  Future<bool> parallelLaunch(String packageName, {int userId = 0}) => _channel
      .invokeMethod<bool>(
          'parallelLaunch', {'package': packageName, 'userId': userId})
      .then((v) => v ?? false);

  /// Hapus satu instance dari mesin parallel (data instance itu saja).
  Future<bool> parallelUninstall(String packageName, {int userId = 0}) =>
      _channel
          .invokeMethod<bool>(
              'parallelUninstall', {'package': packageName, 'userId': userId})
          .then((v) => v ?? false);

  /// Buat ikon layar utama untuk satu instance.
  Future<bool> parallelShortcut(String packageName, {int userId = 0}) =>
      _channel
          .invokeMethod<bool>(
              'parallelShortcut', {'package': packageName, 'userId': userId})
          .then((v) => v ?? false);

  /// Ganti nama tampilan satu instance (kosongkan = kembali otomatis).
  Future<bool> parallelRename(String packageName,
          {int userId = 0, required String label}) =>
      _channel
          .invokeMethod<bool>('parallelRename',
              {'package': packageName, 'userId': userId, 'label': label})
          .then((v) => v ?? false);

  /// Bersihkan data satu instance (clone tetap ada, login terhapus).
  Future<bool> parallelClearData(String packageName, {int userId = 0}) =>
      _channel
          .invokeMethod<bool>(
              'parallelClearData', {'package': packageName, 'userId': userId})
          .then((v) => v ?? false);

  /// Backup data satu instance ke zip; kembalikan {path, sizeBytes}.
  Future<Map<String, dynamic>> parallelBackup(String packageName,
      {int userId = 0}) async {
    try {
      final m = await _channel.invokeMethod<Map<dynamic, dynamic>>(
          'parallelBackup', {'package': packageName, 'userId': userId});
      return {
        'path': (m?['path'] ?? '') as String,
        'sizeBytes': (m?['sizeBytes'] ?? 0) as int,
      };
    } on PlatformException catch (e) {
      throw CloneFailure(e.message ?? 'Backup gagal.');
    }
  }

  /// Pulihkan data satu instance dari zip hasil backup.
  Future<bool> parallelRestore(String packageName,
          {int userId = 0, required String zipPath}) =>
      _channel
          .invokeMethod<bool>('parallelRestore', {
        'package': packageName,
        'userId': userId,
        'zipPath': zipPath,
      })
          .then((v) => v ?? false);
}

/// Kegagalan yang pesannya aman ditampilkan langsung ke pengguna.
class CloneFailure implements Exception {
  const CloneFailure(this.message);

  final String message;

  @override
  String toString() => message;
}
