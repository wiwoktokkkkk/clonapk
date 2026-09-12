import 'dart:async';
import 'dart:io';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
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
  }) async {
    try {
      final res = await _channel.invokeMethod<Map<dynamic, dynamic>>('clone', {
        'sourcePath': sourcePath,
        'newPackage': newPackage,
        'newLabel': newLabel,
        'deepScan': deepScan,
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

  Future<void> installApk(String path) async {
    try {
      await _channel.invokeMethod<bool>('installApk', {'path': path});
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

  Future<String> exportDir() async {
    try {
      final p = await _channel.invokeMethod<String>('exportDir');
      return p ?? '';
    } on PlatformException {
      return '';
    }
  }

  /// Minta izin baca penyimpanan bila diperlukan, lalu buka pemilih berkas.
  ///
  /// @return path APK terpilih, atau null kalau pengguna membatalkan.
  Future<String?> pickApkFile() async {
    if (Platform.isAndroid) {
      final status = await Permission.storage.request();
      // Pada Android 13+ izin storage selalu ditolak tetapi pemilih berkas tetap
      // berfungsi lewat Storage Access Framework, jadi jangan memblokir di sini.
      if (status.isPermanentlyDenied) {
        await openAppSettings();
        return null;
      }
    }
    final res = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: ['apk', 'apks', 'xapk'],
      withData: false,
    );
    final path = res?.files.single.path;
    if (path == null || path.isEmpty) return null;
    return path;
  }
}

/// Kegagalan yang pesannya aman ditampilkan langsung ke pengguna.
class CloneFailure implements Exception {
  const CloneFailure(this.message);

  final String message;

  @override
  String toString() => message;
}
