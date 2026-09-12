/// Hasil proses clone yang berhasil.
class CloneResult {
  const CloneResult({
    required this.path,
    required this.fileName,
    required this.newPackage,
    required this.originalPackage,
    required this.sizeBytes,
    required this.minSdkLowered,
  });

  factory CloneResult.fromMap(Map<dynamic, dynamic> m) => CloneResult(
        path: (m['path'] ?? '') as String,
        fileName: (m['fileName'] ?? '') as String,
        newPackage: (m['newPackage'] ?? '') as String,
        originalPackage: (m['originalPackage'] ?? '') as String,
        sizeBytes: (m['sizeBytes'] ?? 0) as int,
        minSdkLowered: (m['minSdkLowered'] ?? false) as bool,
      );

  final String path;
  final String fileName;
  final String newPackage;
  final String originalPackage;
  final int sizeBytes;
  final bool minSdkLowered;
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
  });

  factory HistoryItem.fromMap(Map<dynamic, dynamic> m) => HistoryItem(
        path: (m['path'] ?? '') as String,
        fileName: (m['fileName'] ?? '') as String,
        sizeBytes: (m['sizeBytes'] ?? 0) as int,
        modifiedAt: (m['modifiedAt'] ?? 0) as int,
      );

  final String path;
  final String fileName;
  final int sizeBytes;
  final int modifiedAt;

  DateTime get modified => DateTime.fromMillisecondsSinceEpoch(modifiedAt);
}
