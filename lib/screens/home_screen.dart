import 'dart:io';

import 'package:flutter/cupertino.dart';

import '../models/app_info.dart';
import '../models/clone_result.dart';
import '../services/clone_service.dart';
import '../widgets/ios_widgets.dart';
import 'clone_sheet.dart';
import 'history_screen.dart';
import 'progress_screen.dart';
import 'result_screen.dart';

/// Layar utama: clone satu-tap dengan identitas otomatis + daftar clone kamu.
class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  final _service = CloneService.instance;
  final _searchController = TextEditingController();

  List<AppInfo> _apps = const [];
  List<HistoryItem> _history = const [];
  String _query = '';
  bool _includeSystem = false;
  bool _loading = true;
  bool _busy = false;
  String? _error;

  // Ruang virtual (profil kerja): clone package sama tanpa install ulang.
  bool _profileOwner = false;
  bool _hasProfile = false;
  bool _useVirtual = false;

  // Mesin parallel (BlackBox, eksperimental): aplikasi jalan di dalam ClonApk.
  List<AppInfo> _parallelApps = const [];

  // Sesuai permintaan: ClonApk fokus "hanya aplikasi dalam mesin".
  // Ruang Virtual & mode APK disembunyikan di balik sakelar ini.
  bool _showFallback = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _searchController.addListener(() {
      setState(() => _query = _searchController.text.trim().toLowerCase());
    });
    _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _searchController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Kembali dari layar persetujuan profil kerja → segarkan status.
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
        _service.getInstalledApps(includeSystem: _includeSystem),
        _service.getHistory(),
        _service.virtualStatus(),
        _service.parallelApps().catchError((_) => <AppInfo>[]),
      ]);
      if (!mounted) return;
      setState(() {
        _apps = results[0] as List<AppInfo>;
        _history = results[1] as List<HistoryItem>;
        final vs = results[2] as Map<String, bool>;
        _profileOwner = vs['profileOwner'] ?? false;
        _hasProfile = vs['hasProfile'] ?? false;
        if (_profileOwner) _useVirtual = true;
        _parallelApps = results[3] as List<AppInfo>;
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

  /// Minta izin sistem untuk membuat profil kerja (satu kali saja).
  Future<void> _provisionVirtual() async {
    final ok = await _service.provisionProfile();
    if (!mounted) return;
    if (!ok) {
      await _dialog('Tidak bisa dibuka',
          'Layar penyiapan profil kerja gagal dibuka oleh sistem.');
      return;
    }
    await _dialog('Selesaikan di layar sistem',
        'Layar "Siapkan profil kerja" akan muncul. Setujui — itu satu-satunya '
        'langkah manual, dan setelah itu ruang virtual aktif selamanya. '
        'Kembali ke ClonApk lalu ketuk Clone pada aplikasi mana pun.');
  }

  /// Dialog informasi/gagal yang konsisten dengan gaya iOS.
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

  /// Package yang sudah punya instance di ruang virtual (dari riwayat).
  Set<String> get _virtualCloned => _history
      .where((h) => h.isVirtual)
      .map((h) => h.originalPackage)
      .toSet();

  /// Daftar aplikasi untuk ditambahkan ke mesin parallel.
  Future<void> _addParallel() async {
    final apps =
        _apps.where((a) => a.packageName != 'com.clonapk.app').toList();
    await showCupertinoModalPopup<void>(
      context: context,
      builder: (ctx) => Container(
        height: MediaQuery.of(ctx).size.height * 0.6,
        color: CupertinoColors.systemGroupedBackground.resolveFrom(ctx),
        child: Column(
          children: [
            const SizedBox(height: 14),
            const Text('Tambah ke mesin parallel',
                style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            Expanded(
              child: ListView(
                children: [
                  for (final app in apps)
                    IosRow(
                      title: app.label,
                      subtitle: app.packageName,
                      leading: AppIconTile(app: app),
                      trailing: IosPillButton(
                        label: 'Tambah',
                        icon: CupertinoIcons.plus_circle,
                        onPressed: () async {
                          Navigator.of(ctx).pop();
                          try {
                            await _service.parallelInstall(app.packageName);
                            // Langsung coba buatkan ikon di layar utama supaya
                            // clone tampil seperti aplikasi biasa.
                            final pinned =
                                await _service.parallelShortcut(app.packageName);
                            _load();
                            await _dialog(
                                'Berhasil ditambahkan',
                                '${app.label} sekarang ada di dalam mesin. '
                                'Data & loginnya tersimpan di penyimpanan '
                                'ClonApk — hapus cache tidak akan '
                                'menghapusnya.${pinned ? '' : ' Ikon layar utama gagal dibuat otomatis; pakai tekan lama pada ikon di grid → "Buat ikon".'}');
                          } on CloneFailure catch (e) {
                            await _dialog('Mesin parallel gagal', e.message);
                          } catch (e) {
                            await _dialog('Mesin parallel gagal', '$e');
                          }
                        },
                      ),
                      dense: true,
                    ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Buka aplikasi di dalam mesin parallel.
  Future<void> _launchParallel(AppInfo app) async {
    final ok = await _service.parallelLaunch(app.packageName);
    if (!ok && mounted) {
      await _dialog('Gagal membuka',
          '${app.label} tidak bisa dijalankan di mesin parallel pada perangkat '
          'ini. Coba Ruang Virtual (lebih andal) atau mode APK.');
    }
  }

  /// Menu tekan-lama pada ubin mesin parallel.
  Future<void> _parallelMenu(AppInfo app) async {
    final choice = await showCupertinoModalPopup<String>(
      context: context,
      builder: (ctx) => CupertinoActionSheet(
        title: Text(app.label),
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
      await _launchParallel(app);
    } else if (choice == 'shortcut') {
      final ok = await _service.parallelShortcut(app.packageName);
      await _dialog(
          ok ? 'Ikon diminta' : 'Gagal membuat ikon',
          ok
              ? 'Permintaan ikon dikirim ke launcher. Sebagian launcher '
                  'menampilkan dialog konfirmasi — setujui di sana.'
              : 'Launcher HP ini tidak mendukung pin ikon otomatis. '
                  'Gunakan menu widget/shortcut launcher secara manual.');
    } else if (choice == 'remove') {
      await _service.parallelUninstall(app.packageName);
      _load();
    }
  }

  List<AppInfo> get _filtered {
    if (_query.isEmpty) return _apps;
    return _apps
        .where((a) =>
            a.label.toLowerCase().contains(_query) ||
            a.packageName.toLowerCase().contains(_query))
        .toList();
  }

  /// Tombol Clone: utamakan ruang virtual (tanpa menyalin APK — hanya data
  /// yang dipisah). Kalau belum aktif, tawarkan mengaktifkan atau mode APK.
  Future<void> _cloneSmart(AppInfo app) async {
    if (_busy) return;
    if (_useVirtual && _profileOwner) {
      await _virtualClone(app);
      return;
    }
    if (!_profileOwner) {
      final choice = await showCupertinoModalPopup<String>(
        context: context,
        builder: (ctx) => CupertinoActionSheet(
          title: Text('Clone ${app.label}'),
          message: const Text('Ruang virtual tidak menyalin APK — hanya datanya '
              'yang dipisah, seperti HP kedua di dalam HP. Mode APK menyalin '
              'aplikasi dengan package baru.'),
          actions: [
            CupertinoActionSheetAction(
              onPressed: () => Navigator.of(ctx).pop('virtual'),
              child: const Text('Aktifkan ruang virtual (disarankan)'),
            ),
            CupertinoActionSheetAction(
              onPressed: () => Navigator.of(ctx).pop('apk'),
              child: const Text('Clone sebagai APK (cadangan)'),
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
      if (choice == 'virtual') {
        await _provisionVirtual();
      } else if (choice == 'apk') {
        await _apkClone(app);
      }
      return;
    }
    await _apkClone(app);
  }

  /// Clone virtual: sudah ada → langsung buka; belum → buat lalu buka.
  Future<void> _virtualClone(AppInfo app) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final has = await _service.virtualHas(app.packageName);
      if (!has) {
        await _service.cloneVirtual(app.packageName);
      }
      final opened = await _service.launchVirtual(app.packageName);
      if (!mounted) return;
      if (!opened) {
        await _dialog('Sudah ada di ruang virtual',
            '${app.label} ada di ruang virtual tapi belum bisa dibuka otomatis. '
            'Cari ikonnya di bagian "Kerja" pada layar utama, atau buka dari '
            'seksi "Clone kamu".');
      }
      _load();
    } on CloneFailure catch (e) {
      if (!mounted) return;
      await _dialog('Ruang virtual gagal', e.message);
    } catch (e) {
      // Jangan biarkan satu kegagalan OEM membuat aplikasi tumbang.
      if (!mounted) return;
      await _dialog('Ruang virtual gagal', '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  /// Mode cadangan: salin APK dengan package otomatis + installer sistem.
  Future<void> _apkClone(AppInfo app) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      final pkg = await _service.nextClonePackage(app.packageName);
      if (!mounted) return;
      final result = await Navigator.of(context).push<CloneResult>(
        CupertinoPageRoute<CloneResult>(
          fullscreenDialog: true,
          builder: (_) => ProgressScreen(
            sourcePath: app.apkPath,
            newPackage: pkg,
            newLabel: app.label,
            splits: app.splitPaths,
          ),
        ),
      );
      if (result != null && mounted) {
        // Satu ketukan di installer sistem adalah satu-satunya langkah manual;
        // Android memang mewajibkan konfirmasi pemasangan.
        await _service.installApk(result.path, extraPaths: result.extraPaths);
        if (!mounted) return;
        await Navigator.of(context).push(
          CupertinoPageRoute<void>(
            builder: (_) => ResultScreen(result: result),
          ),
        );
      }
      _load();
    } on CloneFailure catch (e) {
      if (mounted) {
        showCupertinoDialog<void>(
          context: context,
          builder: (ctx) => CupertinoAlertDialog(
            title: const Text('Clone gagal'),
            content: Text(e.message),
            actions: [
              CupertinoDialogAction(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('Oke'),
              ),
            ],
          ),
        );
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _manualClone(AppInfo app) async {
    await showCloneSheet(context,
        sourcePath: app.apkPath,
        initialLabel: app.label,
        splits: app.splitPaths);
    _load();
  }

  Future<void> _cloneFromFile() async {
    final path = await _service.pickApkFile();
    if (path == null || !mounted) return;
    await showCloneSheet(context, sourcePath: path);
    _load();
  }

  AppInfo? _originalOf(HistoryItem item) {
    for (final a in _apps) {
      if (a.packageName == item.originalPackage) return a;
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final apps = _filtered;
    return CupertinoPageScaffold(
      child: CustomScrollView(
        slivers: [
          CupertinoSliverNavigationBar(
            largeTitle: const Text('ClonApk'),
            automaticallyImplyLeading: false,
            trailing: GestureDetector(
              onTap: () => Navigator.of(context).push(
                CupertinoPageRoute(builder: (_) => const HistoryScreen()),
              ),
              child: const Padding(
                padding: EdgeInsets.only(right: 4),
                child: Icon(CupertinoIcons.time_solid, size: 22),
              ),
            ),
          ),
          if (_showFallback)
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(16, 4, 16, 10),
                child: CupertinoSearchTextField(
                  controller: _searchController,
                  placeholder: 'Cari nama atau package',
                ),
              ),
            ),
          if (_loading)
            const SliverFillRemaining(
              hasScrollBody: false,
              child: Center(child: CupertinoActivityIndicator(radius: 14)),
            )
          else if (_error != null)
            SliverFillRemaining(
              hasScrollBody: false,
              child: _ErrorView(message: _error!, onRetry: _load),
            )
          else ...[
            SliverToBoxAdapter(
              child: IosGroup(
                header: 'Mesin parallel (eksperimental)',
                showSeparators: false,
                children: [
                  IosRow(
                    title: 'Aplikasi di dalam mesin',
                    subtitle: _parallelApps.isEmpty
                        ? 'Berjalan di dalam ClonApk, tanpa install'
                        : '${_parallelApps.length} aplikasi · ketuk untuk buka',
                    leading: const _LeadingIcon(CupertinoIcons.cube_box),
                    trailing: IosPillButton(
                      label: 'Tambah',
                      icon: CupertinoIcons.plus_circle,
                      onPressed: _addParallel,
                    ),
                  ),
                  if (_parallelApps.isNotEmpty)
                    Padding(
                      padding: const EdgeInsets.fromLTRB(12, 4, 12, 14),
                      child: Wrap(
                        spacing: 12,
                        runSpacing: 14,
                        children: [
                          for (final app in _parallelApps)
                            SizedBox(
                              width: 68,
                              child: GestureDetector(
                                behavior: HitTestBehavior.opaque,
                                onTap: () => _launchParallel(app),
                                onLongPress: () => _parallelMenu(app),
                                child: Column(
                                  children: [
                                    ClipRRect(
                                      borderRadius: BorderRadius.circular(13),
                                      child: app.iconPath != null &&
                                              File(app.iconPath!).existsSync()
                                          ? Image.file(File(app.iconPath!),
                                              width: 52, height: 52)
                                          : Container(
                                              width: 52,
                                              height: 52,
                                              color: const Color(0xFFE5E5EA),
                                              child: const Icon(
                                                  CupertinoIcons.cube_box,
                                                  color: Color(0xFF8E8E93)),
                                            ),
                                    ),
                                    const SizedBox(height: 5),
                                    Text(
                                      app.label,
                                      maxLines: 1,
                                      overflow: TextOverflow.ellipsis,
                                      textAlign: TextAlign.center,
                                      style: const TextStyle(fontSize: 11),
                                    ),
                                  ],
                                ),
                              ),
                            ),
                        ],
                      ),
                    ),
                ],
                footer: 'Mesin parallel = aplikasi dijalankan DI DALAM '
                    'ClonApk tanpa install, seperti Parallel Space (memakai '
                    'mesin open-source BlackBox, Apache-2.0). EKSPERIMENTAL: '
                    'belum teruji perangkat; Android baru & aplikasi proteksi '
                    'ketat sering tidak didukung — jika gagal, pakai Ruang '
                    'Virtual atau mode APK. Tekan lama ubin untuk menghapus.',
              ),
            ),
            SliverToBoxAdapter(
              child: IosGroup(
                header: 'Mode cadangan',
                showSeparators: false,
                children: [
                  IosRow(
                    title: 'Ruang Virtual & mode APK',
                    subtitle: _showFallback
                        ? 'Ditampilkan di bawah'
                        : 'Disembunyikan — ClonApk fokus mesin parallel',
                    trailing: CupertinoSwitch(
                      value: _showFallback,
                      onChanged: (v) => setState(() => _showFallback = v),
                    ),
                    dense: true,
                  ),
                ],
              ),
            ),
            if (_showFallback) ...[
            SliverToBoxAdapter(
              child: IosGroup(
                header: 'Ruang virtual',
                children: [
                  IosRow(
                    title: 'Status ruang virtual',
                    subtitle: _profileOwner
                        ? 'Aktif — clone tidak menyalin APK, hanya data dipisah'
                        : _hasProfile
                            ? 'HP sudah punya profil kerja lain — hapus dulu '
                                'atau pakai mode APK'
                            : 'Belum aktif — ketuk "Aktifkan" (sekali saja)',
                    leading: const _LeadingIcon(CupertinoIcons.time_solid),
                    trailing: !_profileOwner
                        ? IosPillButton(
                            label: 'Aktifkan',
                            icon: CupertinoIcons.sparkles,
                            onPressed: _provisionVirtual,
                          )
                        : const Icon(CupertinoIcons.checkmark_alt_circle,
                            size: 22, color: Color(0xFF34C759)),
                  ),
                  if (_profileOwner)
                    IosRow(
                      title: 'Clone lewat ruang virtual',
                      subtitle: 'Tanpa salin APK — langsung buka',
                      trailing: CupertinoSwitch(
                        value: _useVirtual,
                        onChanged: (v) => setState(() => _useVirtual = v),
                      ),
                    ),
                ],
                footer: 'Clone di ruang virtual tidak mengunduh atau menyalin '
                    'APK — aplikasi yang sudah terpasang dipakai ulang dan '
                    'hanya datanya yang dipisah (hemat penyimpanan, seperti '
                    'HP kedua di dalam HP). Perlu sekali persetujuan sistem '
                    '"profil kerja". Android membatasi satu profil kerja per '
                    'perangkat; sebagian aplikasi proteksi ketat bisa menolak '
                    'berjalan di dalamnya — untuk itu pakai mode APK.',
              ),
            ),
            if (_history.isNotEmpty)
              SliverToBoxAdapter(
                child: IosGroup(
                  header: 'Clone kamu',
                  children: [
                    for (final item in _history)
                      IosRow(
                        title: item.label.isNotEmpty ? item.label : item.fileName,
                        subtitle: item.isVirtual
                            ? '${item.originalPackage} · ruang virtual'
                            : item.installed
                                ? '${item.newPackage} · terpasang'
                                : '${item.newPackage} · belum dipasang',
                        leading: _originalOf(item) != null
                            ? AppIconTile(app: _originalOf(item)!)
                            : const Icon(CupertinoIcons.doc_on_doc,
                                size: 26, color: Color(0xFF0A84FF)),
                        trailing: (item.installed || item.isVirtual)
                            ? IosPillButton(
                                label: 'Buka',
                                icon: CupertinoIcons.play_circle,
                                onPressed: () async {
                                  final ok = await _service.openClone(item);
                                  if (!ok && mounted) {
                                    await _dialog(
                                        'Gagal membuka',
                                        item.isVirtual
                                            ? 'Instance ruang virtual tidak '
                                                'ditemukan. Coba buat ulang.'
                                            : 'Clone belum terpasang.');
                                  }
                                },
                              )
                            : IosPillButton(
                                label: 'Pasang',
                                icon: CupertinoIcons.arrow_down_circle,
                                onPressed: () async {
                                  await _service.installApk(item.path,
                                      extraPaths: item.extraPaths);
                                  _load();
                                },
                              ),
                        dense: true,
                      ),
                  ],
                  footer: 'Clone memakai nama & ikon yang sama dengan aplikasi '
                      'aslinya dan berjalan sebagai aplikasi terpisah.',
                ),
              ),
            SliverToBoxAdapter(
              child: IosGroup(
                header: 'Sumber clone',
                children: [
                  IosRow(
                    title: 'Pilih berkas APK',
                    subtitle: 'Ambil APK dari penyimpanan perangkat',
                    leading: const _LeadingIcon(CupertinoIcons.folder_solid),
                    trailing: const Icon(CupertinoIcons.chevron_right,
                        size: 15, color: CupertinoColors.systemGrey3),
                    onTap: _cloneFromFile,
                  ),
                  IosRow(
                    title: 'Tampilkan aplikasi sistem',
                    leading: const _LeadingIcon(CupertinoIcons.gear_solid),
                    trailing: CupertinoSwitch(
                      value: _includeSystem,
                      onChanged: (v) {
                        setState(() => _includeSystem = v);
                        _load();
                      },
                    ),
                  ),
                ],
                footer: 'Ketuk "Clone" untuk clone satu-tap: identitas dibuat '
                    'otomatis dan installer langsung terbuka. Ketuk baris '
                    'aplikasi untuk pengaturan lanjutan.',
              ),
            ),
            SliverToBoxAdapter(
              child: IosGroup(
                header: _query.isEmpty
                    ? 'Aplikasi terpasang (${apps.length})'
                    : 'Hasil pencarian (${apps.length})',
                showSeparators: apps.isNotEmpty,
                children: apps.isEmpty
                    ? [
                        const IosRow(
                          title: 'Tidak ada aplikasi yang cocok',
                          subtitle: 'Coba kata kunci lain',
                        )
                      ]
                    : [
                        for (final app in apps)
                          IosRow(
                            title: app.label,
                            subtitle:
                                '${app.packageName} · ${app.versionName} · ${formatBytes(app.sizeBytes)}',
                            leading: AppIconTile(app: app),
                            trailing: IosPillButton(
                              label: _virtualCloned.contains(app.packageName)
                                  ? 'Buka'
                                  : 'Clone',
                              icon: _virtualCloned.contains(app.packageName)
                                  ? CupertinoIcons.play_circle
                                  : CupertinoIcons.doc_on_doc,
                              onPressed:
                                  app.apkPath.isEmpty || _busy
                                      ? null
                                      : () => _cloneSmart(app),
                            ),
                            onTap: app.apkPath.isEmpty
                                ? null
                                : () => _manualClone(app),
                            dense: true,
                          ),
                      ],
              ),
            ),
            ],
            const SliverToBoxAdapter(child: SizedBox(height: 32)),
          ],
        ],
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
        padding: const EdgeInsets.symmetric(horizontal: 40),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(CupertinoIcons.exclamationmark_triangle,
                size: 42, color: CupertinoColors.systemGrey2),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 15, height: 1.35),
            ),
            const SizedBox(height: 16),
            IosPillButton(label: 'Coba lagi', onPressed: onRetry),
          ],
        ),
      ),
    );
  }
}
