import 'dart:io';

import 'package:flutter/cupertino.dart';

import '../models/app_info.dart';

/// Kartu daftar bergaya "inset grouped" khas iOS: sudut membulat, latar
/// secondarySystemGroupedBackground, dan garis pemisah yang menjorok dari kiri.
class IosGroup extends StatelessWidget {
  const IosGroup({
    super.key,
    required this.children,
    this.header,
    this.footer,
    this.showSeparators = true,
  });

  final List<Widget> children;
  final String? header;
  final String? footer;
  final bool showSeparators;

  @override
  Widget build(BuildContext context) {
    final isDark = CupertinoTheme.brightnessOf(context) == Brightness.dark;
    final cardColor = isDark ? const Color(0xFF1C1C1E) : CupertinoColors.white;
    final separatorColor = isDark
        ? const Color(0xFF38383A)
        : const Color(0xFFC6C6C8).withValues(alpha: 0.6);

    final rows = <Widget>[];
    for (var i = 0; i < children.length; i++) {
      rows.add(children[i]);
      if (showSeparators && i != children.length - 1) {
        rows.add(Padding(
          padding: const EdgeInsets.only(left: 16),
          child: Container(height: 0.5, color: separatorColor),
        ));
      }
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 0),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (header != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 24, 16, 6),
              child: Text(
                header!.toUpperCase(),
                style: TextStyle(
                  fontSize: 13,
                  letterSpacing: -0.08,
                  fontWeight: FontWeight.w400,
                  color: isDark
                      ? const Color(0xFF98989F)
                      : const Color(0xFF6D6D72),
                ),
              ),
            )
          else
            const SizedBox(height: 18),
          ClipRRect(
            borderRadius: BorderRadius.circular(12),
            child: Container(
              color: cardColor,
              child: Column(children: rows),
            ),
          ),
          if (footer != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 6, 16, 0),
              child: Text(
                footer!,
                style: TextStyle(
                  fontSize: 13,
                  height: 1.3,
                  color: isDark
                      ? const Color(0xFF98989F)
                      : const Color(0xFF6D6D72),
                ),
              ),
            ),
        ],
      ),
    );
  }
}

/// Baris daftar standar: ikon di kiri, judul + keterangan di tengah,
/// widget tambahan (chevron/sakelar) di kanan.
class IosRow extends StatelessWidget {
  const IosRow({
    super.key,
    required this.title,
    this.subtitle,
    this.leading,
    this.trailing,
    this.onTap,
    this.titleColor,
    this.dense = false,
  });

  final String title;
  final String? subtitle;
  final Widget? leading;
  final Widget? trailing;
  final VoidCallback? onTap;
  final Color? titleColor;
  final bool dense;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Padding(
        padding: EdgeInsets.symmetric(
          horizontal: 16,
          vertical: dense ? 9 : 11,
        ),
        child: Row(
          children: [
            if (leading != null) ...[
              leading!,
              const SizedBox(width: 12),
            ],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  Text(
                    title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 17,
                      letterSpacing: -0.41,
                      color: titleColor ?? CupertinoTheme.of(context).textTheme.textStyle.color,
                    ),
                  ),
                  if (subtitle != null) ...[
                    const SizedBox(height: 2),
                    Text(
                      subtitle!,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        letterSpacing: -0.08,
                        color: CupertinoTheme.brightnessOf(context) == Brightness.dark
                            ? const Color(0xFF98989F)
                            : const Color(0xFF8A8A8E),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (trailing != null) ...[
              const SizedBox(width: 8),
              trailing!,
            ],
          ],
        ),
      ),
    );
  }
}

/// Ikon aplikasi berbentuk kotak membulat ala iOS.
class AppIconTile extends StatelessWidget {
  const AppIconTile({super.key, required this.app, this.size = 46});

  final AppInfo app;
  final double size;

  @override
  Widget build(BuildContext context) {
    final path = app.iconPath;
    Widget child;
    if (path != null && path.isNotEmpty && File(path).existsSync()) {
      child = Image.file(
        File(path),
        width: size,
        height: size,
        fit: BoxFit.cover,
        gaplessPlayback: true,
        errorBuilder: (_, __, ___) => _fallback(context),
      );
    } else {
      child = _fallback(context);
    }
    return ClipRRect(
      borderRadius: BorderRadius.circular(size * 0.2237),
      child: SizedBox(width: size, height: size, child: child),
    );
  }

  Widget _fallback(BuildContext context) {
    final label = app.label.trim();
    final letter = label.isEmpty ? '?' : label.characters.first.toUpperCase();
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            const Color(0xFF0A84FF),
            const Color(0xFF0A84FF).withValues(alpha: 0.62),
          ],
        ),
      ),
      alignment: Alignment.center,
      child: Text(
        letter,
        style: TextStyle(
          fontSize: size * 0.44,
          fontWeight: FontWeight.w600,
          color: CupertinoColors.white,
        ),
      ),
    );
  }
}

/// Tombol biru kecil bergaya iOS (seperti tombol di baris daftar).
class IosPillButton extends StatelessWidget {
  const IosPillButton({
    super.key,
    required this.label,
    this.onPressed,
    this.destructive = false,
    this.icon,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool destructive;
  final IconData? icon;

  @override
  Widget build(BuildContext context) {
    final color = destructive
        ? CupertinoColors.systemRed.resolveFrom(context)
        : CupertinoTheme.of(context).primaryColor;
    return GestureDetector(
      onTap: onPressed,
      child: Container(
        padding: EdgeInsets.symmetric(
          horizontal: icon == null ? 14 : 12,
          vertical: 7,
        ),
        decoration: BoxDecoration(
          color: color.withValues(alpha: onPressed == null ? 0.25 : 0.13),
          borderRadius: BorderRadius.circular(9),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (icon != null) ...[
              Icon(icon, size: 15, color: color),
              const SizedBox(width: 5),
            ],
            Text(
              label,
              style: TextStyle(
                fontSize: 15,
                fontWeight: FontWeight.w600,
                letterSpacing: -0.24,
                color: color,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Tombol penuh lebar untuk aksi utama di lembar bawah.
class IosPrimaryButton extends StatelessWidget {
  const IosPrimaryButton({
    super.key,
    required this.label,
    this.onPressed,
    this.destructive = false,
  });

  final String label;
  final VoidCallback? onPressed;
  final bool destructive;

  @override
  Widget build(BuildContext context) {
    final color = destructive
        ? CupertinoColors.systemRed.resolveFrom(context)
        : CupertinoTheme.of(context).primaryColor;
    final enabled = onPressed != null;
    return GestureDetector(
      onTap: onPressed,
      child: AnimatedOpacity(
        duration: const Duration(milliseconds: 150),
        opacity: enabled ? 1 : 0.45,
        child: Container(
          height: 50,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: color,
            borderRadius: BorderRadius.circular(13),
          ),
          child: Text(
            label,
            style: const TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w600,
              letterSpacing: -0.41,
              color: CupertinoColors.white,
            ),
          ),
        ),
      ),
    );
  }
}

/// Format ukuran berkas menjadi bentuk yang biasa dipakai iOS (MB/GB).
String formatBytes(int bytes) {
  if (bytes <= 0) return '0 MB';
  const units = ['B', 'KB', 'MB', 'GB'];
  double v = bytes.toDouble();
  var i = 0;
  while (v >= 1024 && i < units.length - 1) {
    v /= 1024;
    i++;
  }
  final digits = v >= 100 || i == 0 ? 0 : 1;
  return '${v.toStringAsFixed(digits)} ${units[i]}';
}
