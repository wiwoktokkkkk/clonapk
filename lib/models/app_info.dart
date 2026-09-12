/// Metadata satu aplikasi yang terpasang di perangkat.
class AppInfo {
  const AppInfo({
    required this.packageName,
    required this.label,
    required this.versionName,
    required this.isSystem,
    required this.apkPath,
    required this.sizeBytes,
    this.iconPath,
  });

  factory AppInfo.fromMap(Map<dynamic, dynamic> m) => AppInfo(
        packageName: (m['packageName'] ?? '') as String,
        label: (m['label'] ?? '') as String,
        versionName: (m['versionName'] ?? '-') as String,
        isSystem: (m['isSystem'] ?? false) as bool,
        apkPath: (m['apkPath'] ?? '') as String,
        sizeBytes: (m['sizeBytes'] ?? 0) as int,
        iconPath: m['iconPath'] as String?,
      );

  final String packageName;
  final String label;
  final String versionName;
  final bool isSystem;
  final String apkPath;
  final int sizeBytes;
  final String? iconPath;

  /// Nama berkas APK sumber, untuk ditampilkan di baris detail.
  String get sourceFileName {
    if (apkPath.isEmpty) return '-';
    final i = apkPath.lastIndexOf('/');
    return i < 0 ? apkPath : apkPath.substring(i + 1);
  }
}
