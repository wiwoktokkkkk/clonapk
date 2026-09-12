import 'package:flutter/cupertino.dart';

import '../models/clone_result.dart';
import '../services/clone_service.dart';
import '../widgets/ios_widgets.dart';
import 'result_screen.dart';

/// Daftar APK hasil clone yang masih ada di penyimpanan.
class HistoryScreen extends StatefulWidget {
  const HistoryScreen({super.key});

  @override
  State<HistoryScreen> createState() => _HistoryScreenState();
}

class _HistoryScreenState extends State<HistoryScreen> {
  final _service = CloneService.instance;

  List<HistoryItem> _items = const [];
  bool _loading = true;
  String? _error;
  String _dir = '';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final items = await _service.getHistory();
      final dir = await _service.exportDir();
      if (!mounted) return;
      setState(() {
        _items = items;
        _dir = dir;
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

  Future<void> _open(HistoryItem item) async {
    await Navigator.of(context).push(
      CupertinoPageRoute<void>(
        builder: (_) => ResultScreen(
          result: CloneResult(
            path: item.path,
            fileName: item.fileName,
            newPackage: '-',
            originalPackage: '-',
            sizeBytes: item.sizeBytes,
            minSdkLowered: false,
          ),
        ),
      ),
    );
    _load();
  }

  Future<void> _delete(HistoryItem item) async {
    final confirmed = await showCupertinoDialog<bool>(
      context: context,
      builder: (ctx) => CupertinoAlertDialog(
        title: const Text('Hapus berkas ini?'),
        content: Text(item.fileName),
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
    if (confirmed != true) return;
    try {
      await _service.deleteApk(item.path);
      _load();
    } on CloneFailure catch (e) {
      if (mounted) setState(() => _error = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    return CupertinoPageScaffold(
      navigationBar: const CupertinoNavigationBar(
        middle: Text('Hasil Clone'),
        automaticallyImplyLeading: true,
      ),
      child: SafeArea(
        child: _loading
            ? const Center(child: CupertinoActivityIndicator(radius: 14))
            : CustomScrollView(
                slivers: [
                  SliverToBoxAdapter(
                    child: IosGroup(
                      header: 'Tersimpan (${_items.length})',
                      children: _items.isEmpty
                          ? const [
                              IosRow(
                                title: 'Belum ada hasil clone',
                                subtitle: 'Clone pertama Anda akan muncul di sini',
                              )
                            ]
                          : [
                              for (final item in _items)
                                Dismissible(
                                  key: ValueKey(item.path),
                                  direction: DismissDirection.endToStart,
                                  onDismissed: (_) => _delete(item),
                                  background: Container(
                                    color: CupertinoColors.systemRed
                                        .resolveFrom(context),
                                    alignment: Alignment.centerRight,
                                    padding: const EdgeInsets.only(right: 22),
                                    child: const Icon(CupertinoIcons.trash,
                                        color: CupertinoColors.white),
                                  ),
                                  child: IosRow(
                                    title: item.fileName,
                                    subtitle:
                                        '${formatBytes(item.sizeBytes)} · ${_fmt(item.modified)}',
                                    leading: const Icon(
                                      CupertinoIcons.doc_zipper,
                                      size: 26,
                                      color: Color(0xFF0A84FF),
                                    ),
                                    trailing: const Icon(
                                        CupertinoIcons.chevron_right,
                                        size: 15,
                                        color: CupertinoColors.systemGrey3),
                                    onTap: () => _open(item),
                                    dense: true,
                                  ),
                                ),
                            ],
                      footer: _error ??
                          (_dir.isEmpty
                              ? null
                              : 'Lokasi penyimpanan: $_dir'),
                    ),
                  ),
                  const SliverToBoxAdapter(child: SizedBox(height: 32)),
                ],
              ),
      ),
    );
  }

  static String _fmt(DateTime d) {
    String two(int v) => v.toString().padLeft(2, '0');
    return '${two(d.day)}/${two(d.month)}/${d.year} ${two(d.hour)}:${two(d.minute)}';
  }
}
