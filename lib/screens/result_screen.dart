import 'package:flutter/cupertino.dart';

import '../models/clone_result.dart';
import '../services/clone_service.dart';
import '../widgets/ios_widgets.dart';

/// Layar hasil clone: ringkasan dan aksi lanjutan (pasang, bagikan, hapus).
class ResultScreen extends StatefulWidget {
  const ResultScreen({super.key, required this.result});

  final CloneResult result;

  @override
  State<ResultScreen> createState() => _ResultScreenState();
}

class _ResultScreenState extends State<ResultScreen> {
  final _service = CloneService.instance;
  bool _busy = false;
  String? _error;
  bool _deleted = false;

  Future<void> _guard(Future<void> Function() action) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
    } on CloneFailure catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _confirmDelete() async {
    final confirmed = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Hapus berkas ini?'),
        content: Text(widget.result.fileName),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Batal'),
          ),
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Hapus'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    await _guard(() async {
      final ok = await _service.deleteApk(widget.result.path);
      if (ok && mounted) {
        setState(() => _deleted = true);
        Navigator.of(context).pop();
      } else if (!ok && mounted) {
        setState(() => _error = 'Berkas tidak bisa dihapus.');
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final r = widget.result;
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        middle: const Text('Hasil Clone'),
        trailing: GestureDetector(
          onTap: () => Navigator.of(context).pop(),
          child: const Text(
            'Selesai',
            style: TextStyle(fontSize: 17, color: Color(0xFF0A84FF)),
          ),
        ),
        automaticallyImplyLeading: false,
      ),
      child: SafeArea(
        child: SingleChildScrollView(
          child: Column(
            children: [
              Padding(
                padding: const EdgeInsets.only(top: 28, bottom: 6),
                child: Container(
                  width: 74,
                  height: 74,
                  decoration: BoxDecoration(
                    color: _deleted
                        ? CupertinoColors.systemGrey
                        : CupertinoColors.systemGreen.resolveFrom(context),
                    shape: BoxShape.circle,
                  ),
                  child: Icon(
                    _deleted
                        ? CupertinoIcons.trash
                        : CupertinoIcons.checkmark_alt,
                    size: 40,
                    color: CupertinoColors.white,
                  ),
                ),
              ),
              const SizedBox(height: 14),
              Text(
                _deleted ? 'Berkas dihapus' : 'Clone berhasil dibuat',
                style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w700),
              ),
              const SizedBox(height: 6),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 36),
                child: Text(
                  _deleted
                      ? 'APK hasil clone sudah dibuang dari penyimpanan.'
                      : 'APK sudah ditandatangani ulang dengan kunci ClonApk dan siap dipasang.',
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontSize: 15,
                    height: 1.35,
                    color: CupertinoColors.secondaryLabel.resolveFrom(context),
                  ),
                ),
              ),
              IosGroup(
                header: 'Detail',
                children: [
                  IosRow(
                    title: r.fileName,
                    subtitle: formatBytes(r.sizeBytes),
                    dense: true,
                  ),
                  IosRow(
                    title: 'Package baru',
                    subtitle: r.newPackage,
                    dense: true,
                  ),
                  IosRow(
                    title: 'Package asli',
                    subtitle: r.originalPackage,
                    dense: true,
                  ),
                  if (r.minSdkLowered)
                    const IosRow(
                      title: 'minSdkVersion diturunkan ke 23',
                      subtitle: 'Diperlukan karena APK ditandatangani skema v1',
                      dense: true,
                    ),
                ],
                footer: _error,
              ),
              if (!_deleted) ...[
                IosGroup(
                  header: 'Aksi',
                  children: [
                    IosRow(
                      title: 'Pasang sekarang',
                      subtitle: 'Buka pemasang Android',
                      leading: const _Icon(CupertinoIcons.arrow_down_circle,
                          color: CupertinoColors.activeGreen),
                      trailing: const Icon(CupertinoIcons.chevron_right,
                          size: 15, color: CupertinoColors.systemGrey3),
                      onTap: _busy
                          ? null
                          : () => _guard(() => _service.installApk(r.path)),
                    ),
                    IosRow(
                      title: 'Bagikan APK',
                      leading: const _Icon(CupertinoIcons.share_solid,
                          color: Color(0xFF0A84FF)),
                      trailing: const Icon(CupertinoIcons.chevron_right,
                          size: 15, color: CupertinoColors.systemGrey3),
                      onTap: _busy
                          ? null
                          : () => _guard(() => _service.shareApk(r.path)),
                    ),
                    IosRow(
                      title: 'Hapus berkas',
                      titleColor: CupertinoColors.systemRed.resolveFrom(context),
                      leading: const _Icon(CupertinoIcons.trash,
                          color: CupertinoColors.systemRed),
                      onTap: _busy ? null : _confirmDelete,
                    ),
                  ],
                  footer: 'Kalau pemasangan ditolak, matikan dulu verifikasi '
                      'Google Play Protect atau izinkan pemasangan dari sumber ini.',
                ),
              ],
              const SizedBox(height: 32),
            ],
          ),
        ),
      ),
    );
  }
}

class _Icon extends StatelessWidget {
  const _Icon(this.icon, {required this.color});

  final IconData icon;
  final Color color;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 30,
      height: 30,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(7),
      ),
      child: Icon(icon, size: 17, color: CupertinoColors.white),
    );
  }
}
