/// Validasi dan usulan applicationId.
///
/// Aturannya sengaja identik dengan [ApkUtil] di sisi Android supaya pengguna
/// langsung mendapat koreksi sebelum proses clone dimulai, bukan setelahnya.
class PackageNameRules {
  PackageNameRules._();

  static const List<String> reserved = <String>[
    'abstract', 'assert', 'boolean', 'break', 'byte', 'case', 'catch', 'char',
    'class', 'const', 'continue', 'default', 'do', 'double', 'else', 'enum',
    'extends', 'final', 'finally', 'float', 'for', 'goto', 'if', 'implements',
    'import', 'instanceof', 'int', 'interface', 'long', 'native', 'new',
    'package', 'private', 'protected', 'public', 'return', 'short', 'static',
    'strictfp', 'super', 'switch', 'synchronized', 'this', 'throw', 'throws',
    'transient', 'try', 'void', 'volatile', 'while', 'true', 'false', 'null',
  ];

  static final RegExp _segment = RegExp(r'^[A-Za-z][A-Za-z0-9_]*$');

  /// @return null bila valid, atau pesan kesalahan bila tidak.
  static String? validate(String pkg, {String? originalPackage}) {
    if (pkg.isEmpty) return 'Nama package tidak boleh kosong.';
    if (pkg.length > 200) return 'Nama package terlalu panjang (maks 200 karakter).';
    if (pkg.startsWith('android')) {
      return "Nama package tidak boleh diawali 'android' — dicadangkan sistem.";
    }
    final parts = pkg.split('.');
    if (parts.length < 2) {
      return 'Nama package butuh minimal dua segmen, contoh: com.clonapk.clone';
    }
    for (final p in parts) {
      if (!_segment.hasMatch(p)) {
        return "Segmen '$p' tidak valid. Tiap segmen harus diawali huruf dan "
            'hanya berisi huruf, angka, atau underscore.';
      }
      if (reserved.contains(p)) {
        return "Segmen '$p' adalah kata kunci Java dan tidak bisa dipakai.";
      }
    }
    if (originalPackage != null && originalPackage == pkg) {
      return 'Nama package baru harus berbeda dari yang asli.';
    }
    return null;
  }

  static String suggest(String original, String suffix) {
    var s = suffix.trim().toLowerCase().replaceAll(RegExp(r'[^a-z0-9_]'), '');
    if (s.isEmpty) s = 'clone';
    if (RegExp(r'^[0-9]').hasMatch(s)) s = 'c$s';
    return '$original.$s';
  }
}
