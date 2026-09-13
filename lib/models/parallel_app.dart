/// Satu instance aplikasi di dalam mesin parallel.
///
/// Package yang sama bisa punya beberapa instance (userId berbeda):
/// "WA 1", "WA 2", dst. — masing-masing dengan data terpisah.
class ParallelApp {
  const ParallelApp({
    required this.packageName,
    required this.userId,
    required this.label,
    this.iconPath,
  });

  factory ParallelApp.fromMap(Map<dynamic, dynamic> m) => ParallelApp(
        packageName: (m['packageName'] ?? '') as String,
        userId: (m['userId'] ?? 0) as int,
        label: (m['label'] ?? '') as String,
        iconPath: m['iconPath'] as String?,
      );

  final String packageName;
  final int userId;
  final String label;
  final String? iconPath;
}
