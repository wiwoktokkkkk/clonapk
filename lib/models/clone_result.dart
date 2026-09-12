/// Hasil proses clone yang berhasil.
class CloneResult {
  const CloneResult({
    required this.path,
    required this.fileName,
    required this.newPackage,
    required this.originalPackage,
    required this.sizeBytes,
    required this.minSdkLowered,
    this.extraPaths = const [],
  });

  factory CloneResult.fromMap(Map<dynamic, dynamic> m) => CloneResult(
        path: (m['path'] ?? '') as String,
        fileName: (m['fileName'] ?? '') as String,
        newPackage: (m['newPackage'] ?? '') as String,
        originalPackage: (m['originalPackage'] ?? '') as String,
        sizeBytes: (m['sizeBytes'] ?? 0) as int,
        minSdkLowered: (m['minSdkLowered'] ?? false) as bool,
        extraPaths: ((m['extraPaths'] ?? const <dynamic>[]) as List)
            .map((e) => e.toString())
            .toList(),
      );

  final String path;
  final String fileName;
  final String newPackage;
  final String originalPackage;
  final int sizeBytes;
  final bool minSdkLowered;

  /// Bagian split yang ikut di-clone; dipasang bersama base dalam satu sesi.
  final List<String> extraPaths;
}

/// Perkembangan proses clone yang dikirim dari sisi Android.
class CloneProgress {
  const CloneProgress(this.stage, this.percent);

  factory CloneProgress.fromMap(Map<dynamic, dynamic> m) => CloneProgress(
        (m['stage'] ?? 'Menyiapkan') as String,
        (m['percent'] ?? 0) as int,
      );

  final String stage;
  final int percent;

  double get fraction => (percent.clamp(0, 100)) / 100.0;
}

/// Informasi APK yang dibaca sebelum proses clone.
class ApkInspection {
  const ApkInspection({
    required this.packageName,
    required this.versionName,
    required this.sizeBytes,
    required this.permissions,
    required this.suggestedPackage,
  });

  factory ApkInspection.fromMap(Map<dynamic, dynamic> m) => ApkInspection(
        packageName: (m['packageName'] ?? '') as String,
        versionName: (m['versionName'] ?? '-') as String,
        sizeBytes: (m['sizeBytes'] ?? 0) as int,
        permissions: ((m['permissions'] ?? const <String>[]) as List)
            .map((e) => e.toString())
            .toList(),
        suggestedPackage: (m['suggestedPackage'] ?? '') as String,
      );

  final String packageName;
  final String versionName;
  final int sizeBytes;
  final List<String> permissions;
  final String suggestedPackage;
}

/// Baris pada riwayat clone.
class HistoryItem {
  const HistoryItem({
    required this.path,
    required this.fileName,
    required this.sizeBytes,
    required this.modifiedAt,
    this.label = '',
    this.originalPackage = '',
    this.newPackage = '',
    this.installed = false,
    this.type = 'apk',
    this.extraPaths = const [],
  });

  factory HistoryItem.fromMap(Map<dynamic, dynamic> m) => HistoryItem(
        path: (m['path'] ?? '') as String,
        fileName: (m['fileName'] ?? '') as String,
        sizeBytes: (m['sizeBytes'] ?? 0) as int,
        modifiedAt: (m['modifiedAt'] ?? 0) as int,
        label: (m['label'] ?? '') as String,
        originalPackage: (m['originalPackage'] ?? '') as String,
        newPackage: (m['newPackage'] ?? '') as String,
        installed: (m['installed'] ?? false) as bool,
        type: (m['type'] ?? 'apk') as String,
        extraPaths: ((m['extraPaths'] ?? const <dynamic>[]) as List)
            .map((e) => e.toString())
            .toList(),
      );

  final String path;
  final String fileName;
  final int sizeBytes;
  final int modifiedAt;
  final String label;
  final String originalPackage;
  final String newPackage;
  final bool installed;
  final List<String> extraPaths;

  /// 'apk' (clone package baru) atau 'virtual' (package sama di ruang virtual).
  final String type;

  bool get isVirtual => type == 'virtual';

  DateTime get modified => DateTime.fromMillisecondsSinceEpoch(modifiedAt);
}
