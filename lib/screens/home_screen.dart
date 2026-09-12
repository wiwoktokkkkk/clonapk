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

class _HomeScreenState extends State<HomeScreen> {
  final _service = CloneService.instance;
  final _searchController = TextEditingController();

  List<AppInfo> _apps = const [];
  List<HistoryItem> _history = const [];
  String _query = '';
  bool _includeSystem = false;
  bool _loading = true;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _searchController.addListener(() {
      setState(() => _query = _searchController.text.trim().toLowerCase());
    });
    _load();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
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
      ]);
      if (!mounted) return;
      setState(() {
        _apps = results[0] as List<AppInfo>;
        _history = results[1] as List<HistoryItem>;
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

  List<AppInfo> get _filtered {
    if (_query.isEmpty) return _apps;
    return _apps
        .where((a) =>
            a.label.toLowerCase().contains(_query) ||
            a.packageName.toLowerCase().contains(_query))
        .toList();
  }

  /// Clone satu-tap: package dibuat otomatis, installer langsung dibuka.
  Future<void> _oneTapClone(AppInfo app) async {
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
          ),
        ),
      );
      if (result != null && mounted) {
        // Satu ketukan di installer sistem adalah satu-satunya langkah manual;
        // Android memang mewajibkan konfirmasi pemasangan.
        await _service.installApk(result.path);
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
    await showCloneSheet(context, sourcePath: app.apkPath, initialLabel: app.label);
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
            if (_history.isNotEmpty)
              SliverToBoxAdapter(
                child: IosGroup(
                  header: 'Clone kamu',
                  children: [
                    for (final item in _history)
                      IosRow(
                        title: item.label.isNotEmpty ? item.label : item.fileName,
                        subtitle: item.installed
                            ? '${item.newPackage} · terpasang'
                            : '${item.newPackage} · belum dipasang',
                        leading: _originalOf(item) != null
                            ? AppIconTile(app: _originalOf(item)!)
                            : const Icon(CupertinoIcons.doc_on_doc,
                                size: 26, color: Color(0xFF0A84FF)),
                        trailing: item.installed
                            ? IosPillButton(
                                label: 'Buka',
                                icon: CupertinoIcons.play_circle,
                                onPressed: () => _service.launchApp(item.newPackage),
                              )
                            : IosPillButton(
                                label: 'Pasang',
                                icon: CupertinoIcons.arrow_down_circle,
                                onPressed: () async {
                                  await _service.installApk(item.path);
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
                              label: 'Clone',
                              icon: CupertinoIcons.doc_on_doc,
                              onPressed:
                                  app.apkPath.isEmpty || _busy
                                      ? null
                                      : () => _oneTapClone(app),
                            ),
                            onTap: app.apkPath.isEmpty
                                ? null
                                : () => _manualClone(app),
                            dense: true,
                          ),
                      ],
              ),
            ),
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
