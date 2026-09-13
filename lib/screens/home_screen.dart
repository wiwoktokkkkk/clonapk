import 'dart:io';

import 'package:flutter/cupertino.dart';

import '../models/app_info.dart';
import '../models/parallel_app.dart';
import '../services/clone_service.dart';
import '../widgets/ios_widgets.dart';

/// Beranda ClonApk: murni mesin parallel.
///
/// Sesuai permintaan pengguna, Ruang Virtual dan mode APK dihilangkan —
/// satu-satunya fitur adalah aplikasi DI DALAM mesin:
///  - tambah aplikasi (boleh berkali-kali: "WA 1", "WA 2", ...),
///  - cari aplikasi di daftar tambah,
///  - buka (ketuk), buat ikon layar utama (menu), hapus instance (✕).
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final _service = CloneService.instance;

  List<AppInfo> _apps = const [];
  List<ParallelApp> _instances = const [];
  bool _loading = true;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _load();
    }
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final results = await Future.wait([
        _service.getInstalledApps(includeSystem: false),
        _service.parallelApps().catchError((_) => <ParallelApp>[]),
      ]);
      if (!mounted) return;
      setState(() {
        _apps = results[0] as List<AppInfo>;
        _instances = results[1] as List<ParallelApp>;
        _loading = false;
      });
    } on CloneFailure catch (e) {
      if (!mounted) return;
      setState(() {
        _error = e.message;
        _loading = false;
      });
    }
  }

  // ------------------------------------------------------------------ tambah

  /// Lembar "Tambah aplikasi": daftar lengkap + pencarian.
  Future<void> _addSheet() async {
    await showCupertinoModalPopup<void>(
      context: context,
      builder: (_) => _AddAppSheet(apps: _apps, onPick: _addApp),
    );
  }

  /// Masukkan aplikasi ke mesin → selalu instance baru (WA 1, WA 2, ...).
  Future<void> _addApp(AppInfo app) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final res = await _service.parallelInstall(app.packageName);
      final userId = res['userId'] as int;
      // Langsung minta ikon layar utama untuk instance baru ini.
      final pinned =
          await _service.parallelShortcut(app.packageName, userId: userId);
      _load();
      await _dialog(
          'Berhasil ditambahkan',
          '${app.label} ${userId + 1} sekarang ada di dalam mesin dengan data '
          'terpisah. Login/sesi tersimpan di penyimpanan ClonApk — hapus '
          'cache tidak menghapusnya. Tambahkan lagi untuk membuat '
          '${app.label} ${userId + 2}.'
          '${pinned ? '' : ' Ikon layar utama gagal dibuat otomatis; pakai tekan lama pada ikon → "Buat ikon".'}');
    } on CloneFailure catch (e) {
      await _dialog('Mesin parallel gagal', e.message);
    } catch (e) {
      await _dialog('Mesin parallel gagal', '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  // ------------------------------------------------------------- per instance

  Future<void> _launchInst(ParallelApp inst) async {
    final ok =
        await _service.parallelLaunch(inst.packageName, userId: inst.userId);
    if (!ok && mounted) {
      await _dialog('Gagal membuka',
          '${inst.label} tidak bisa dijalankan di mesin parallel pada '
          'perangkat ini. Mesin ini eksperimental — Android baru dan '
          'aplikasi berproteksi ketat sering tidak didukung.');
    }
  }

  Future<void> _removeInst(ParallelApp inst) async {
    final sure = await _confirm(
        'Hapus ${inst.label}?',
        'Data instance ini (login, chat, dll.) akan dihapus permanen dari '
        'mesin. Instance lain dan aplikasi aslinya tidak terpengaruh.');
    if (sure != true) return;
    await _service.parallelUninstall(inst.packageName, userId: inst.userId);
    _load();
  }

  Future<void> _shortcutInst(ParallelApp inst) async {
    final ok = await _service.parallelShortcut(inst.packageName,
        userId: inst.userId);
    await _dialog(
        ok ? 'Ikon diminta' : 'Gagal membuat ikon',
        ok
            ? 'Permintaan ikon ${inst.label} dikirim ke launcher. Sebagian '
                'launcher menampilkan dialog konfirmasi — setujui di sana.'
            : 'Launcher HP ini tidak mendukung pin ikon otomatis. Gunakan '
                'menu shortcut launcher secara manual.');
  }

  /// Menu tekan-lama pada ubin instance.
  Future<void> _menuInst(ParallelApp inst) async {
    final choice = await showCupertinoModalPopup<String>(
      context: context,
      builder: (ctx) => CupertinoActionSheet(
        title: Text(inst.label),
        actions: [
          CupertinoActionSheetAction(
            onPressed: () => Navigator.of(ctx).pop('open'),
            child: const Text('Buka'),
          ),
          CupertinoActionSheetAction(
            onPressed: () => Navigator.of(ctx).pop('shortcut'),
            child: const Text('Buat ikon di layar utama'),
          ),
          CupertinoActionSheetAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.of(ctx).pop('remove'),
            child: const Text('Hapus dari mesin'),
          ),
        ],
        cancelButton: CupertinoActionSheetAction(
          isDefaultAction: true,
          onPressed: () => Navigator.of(ctx).pop(),
          child: const Text('Batal'),
        ),
      ),
    );
    if (!mounted) return;
    if (choice == 'open') {
      await _launchInst(inst);
    } else if (choice == 'shortcut') {
      await _shortcutInst(inst);
    } else if (choice == 'remove') {
      await _removeInst(inst);
    }
  }

  // ------------------------------------------------------------------- dialog

  Future<void> _dialog(String title, String message) async {
    if (!mounted) return;
    await showCupertinoDialog<void>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          CupertinoDialogAction(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('Oke'),
          ),
        ],
      ),
    );
  }

  Future<bool?> _confirm(String title, String message) {
    return showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          CupertinoDialogAction(
            isDestructiveAction: true,
            onPressed: () => Navigator.of(ctx).pop(true),
            child: const Text('Hapus'),
          ),
          CupertinoDialogAction(
            isDefaultAction: true,
            onPressed: () => Navigator.of(ctx).pop(false),
            child: const Text('Batal'),
          ),
        ],
      ),
    );
  }

  // --------------------------------------------------------------------- view

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(
        middle: Text('ClonApk'),
      ),
      child: SafeArea(
        child: _loading
            ? const Center(child: CupertinoActivityIndicator(radius: 14))
            : _error != null
                ? _ErrorView(message: _error!, onRetry: _load)
                : CustomScrollView(
                    slivers: [
                      SliverToBoxAdapter(
                        child: IosGroup(
                          header: 'Aplikasi dalam mesin',
                          showSeparators: _instances.isEmpty,
                          children: [
                            IosRow(
                              title: 'Tambah aplikasi',
                              subtitle: 'Bisa berkali-kali — WA 1, WA 2, dst.',
                              leading:
                                  const _LeadingIcon(CupertinoIcons.plus_circle),
                              trailing: IosPillButton(
                                label: 'Tambah',
                                icon: CupertinoIcons.search,
                                onPressed: _busy ? null : _addSheet,
                              ),
                              onTap: _busy ? null : _addSheet,
                            ),
                            if (_instances.isEmpty)
                              const IosRow(
                                title: 'Mesin masih kosong',
                                subtitle:
                                    'Tambahkan aplikasi pertama — jalannya di '
                                    'dalam ClonApk, tanpa install',
                              )
                            else
                              Padding(
                                padding:
                                    const EdgeInsets.fromLTRB(12, 6, 12, 16),
                                child: Wrap(
                                  spacing: 14,
                                  runSpacing: 16,
                                  children: [
                                    for (final inst in _instances)
                                      _InstanceTile(
                                        inst: inst,
                                        onTap: () => _launchInst(inst),
                                        onLongPress: () => _menuInst(inst),
                                        onRemove: () => _removeInst(inst),
                                      ),
                                  ],
                                ),
                              ),
                          ],
                          footer: 'Ketuk ikon = buka di dalam mesin. '
                              'Tekan lama = menu (ikon layar utama / hapus). '
                              '✕ = hapus instance itu saja (kalau salah '
                              'tambah). Data tiap instance terpisah dan '
                              'tersimpan di ClonApk — hapus cache tidak '
                              'menghapusnya. Mesin parallel bersifat '
                              'eksperimental: Android baru & aplikasi proteksi '
                              'ketat bisa menolak berjalan.',
                        ),
                      ),
                      const SliverToBoxAdapter(child: SizedBox(height: 32)),
                    ],
                  ),
      ),
    );
  }
}

// ------------------------------------------------------------------ lembar tambah

class _AddAppSheet extends StatefulWidget {
  const _AddAppSheet({required this.apps, required this.onPick});

  final List<AppInfo> apps;
  final Future<void> Function(AppInfo) onPick;

  @override
  State<_AddAppSheet> createState() => _AddAppSheetState();
}

class _AddAppSheetState extends State<_AddAppSheet> {
  final _searchController = TextEditingController();
  String _query = '';

  @override
  void initState() {
    super.initState();
    _searchController.addListener(() {
      setState(() => _query = _searchController.text.trim().toLowerCase());
    });
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  List<AppInfo> get _filtered {
    final all =
        widget.apps.where((a) => a.packageName != 'com.clonapk.app').toList();
    if (_query.isEmpty) return all;
    return all
        .where((a) =>
            a.label.toLowerCase().contains(_query) ||
            a.packageName.toLowerCase().contains(_query))
        .toList();
  }

  @override
  Widget build(BuildContext context) {
    final apps = _filtered;
    return Container(
      height: MediaQuery.of(context).size.height * 0.72,
      color: CupertinoColors.systemGroupedBackground.resolveFrom(context),
      child: Column(
        children: [
          const SizedBox(height: 14),
          const Text('Tambah aplikasi ke mesin',
              style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
            child: CupertinoSearchTextField(
              controller: _searchController,
              placeholder: 'Cari nama atau package',
            ),
          ),
          Expanded(
            child: apps.isEmpty
                ? const Center(
                    child: Text('Tidak ada aplikasi yang cocok',
                        style: TextStyle(color: CupertinoColors.systemGrey)),
                  )
                : ListView.builder(
                    itemCount: apps.length,
                    itemBuilder: (_, i) {
                      final app = apps[i];
                      return IosRow(
                        title: app.label,
                        subtitle:
                            '${app.packageName} · ${app.versionName}',
                        leading: AppIconTile(app: app),
                        trailing: IosPillButton(
                          label: 'Tambah',
                          icon: CupertinoIcons.plus_circle,
                          onPressed: () async {
                            Navigator.of(context).pop();
                            await widget.onPick(app);
                          },
                        ),
                        dense: true,
                      );
                    },
                  ),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------- ubin instance

class _InstanceTile extends StatelessWidget {
  const _InstanceTile({
    required this.inst,
    required this.onTap,
    required this.onLongPress,
    required this.onRemove,
  });

  final ParallelApp inst;
  final VoidCallback onTap;
  final VoidCallback onLongPress;
  final VoidCallback onRemove;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 74,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        onLongPress: onLongPress,
        child: Column(
          children: [
            Stack(
              clipBehavior: Clip.none,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(14),
                  child: inst.iconPath != null &&
                          File(inst.iconPath!).existsSync()
                      ? Image.file(File(inst.iconPath!), width: 56, height: 56)
                      : Container(
                          width: 56,
                          height: 56,
                          color: const Color(0xFFE5E5EA),
                          child: const Icon(CupertinoIcons.cube_box,
                              color: Color(0xFF8E8E93)),
                        ),
                ),
                Positioned(
                  top: -6,
                  right: -6,
                  child: GestureDetector(
                    onTap: onRemove,
                    child: Container(
                      width: 20,
                      height: 20,
                      decoration: const BoxDecoration(
                        color: Color(0xFFFF3B30),
                        shape: BoxShape.circle,
                      ),
                      child: const Icon(CupertinoIcons.xmark,
                          size: 11, color: CupertinoColors.white),
                    ),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              inst.label,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 11),
            ),
          ],
        ),
      ),
    );
  }
}

class _LeadingIcon extends StatelessWidget {
  const _LeadingIcon(this.icon);

  final IconData icon;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 30,
      height: 30,
      decoration: BoxDecoration(
        color: CupertinoTheme.of(context).primaryColor,
        borderRadius: BorderRadius.circular(7),
      ),
      child: Icon(icon, size: 17, color: CupertinoColors.white),
    );
  }
}

class _ErrorView extends StatelessWidget {
  const _ErrorView({required this.message, required this.onRetry});

  final String message;
  final VoidCallback onRetry;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(CupertinoIcons.exclamationmark_triangle,
                size: 40, color: Color(0xFFFF9500)),
            const SizedBox(height: 12),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            IosPillButton(
              label: 'Coba lagi',
              icon: CupertinoIcons.refresh,
              onPressed: onRetry,
            ),
          ],
        ),
      ),
    );
  }
}
