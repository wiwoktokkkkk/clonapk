import 'package:flutter/cupertino.dart';

import '../models/clone_result.dart';
import '../services/clone_service.dart';
import '../services/package_name.dart';
import '../widgets/ios_widgets.dart';
import 'progress_screen.dart';

/// Membuka lembar bawah untuk mengatur identitas clone lalu memulai prosesnya.
Future<void> showCloneSheet(
  BuildContext context, {
  required String sourcePath,
  String? initialLabel,
}) {
  return showCupertinoModalPopup<void>(
    context: context,
    builder: (_) => _CloneSheet(
      sourcePath: sourcePath,
      initialLabel: initialLabel,
    ),
  );
}

class _CloneSheet extends StatefulWidget {
  const _CloneSheet({required this.sourcePath, this.initialLabel});

  final String sourcePath;
  final String? initialLabel;

  @override
  State<_CloneSheet> createState() => _CloneSheetState();
}

class _CloneSheetState extends State<_CloneSheet> {
  final _service = CloneService.instance;
  final _nameController = TextEditingController();
  final _packageController = TextEditingController();
  final _focusNode = FocusNode();

  ApkInspection? _info;
  String? _loadError;
  String? _packageError;
  bool _autoSuffix = true;
  bool _deepScan = false;
  bool _starting = false;

  @override
  void initState() {
    super.initState();
    _packageController.addListener(_onPackageChanged);
    _load();
  }

  @override
  void dispose() {
    _packageController.removeListener(_onPackageChanged);
    _nameController.dispose();
    _packageController.dispose();
    _focusNode.dispose();
    super.dispose();
  }

  void _onPackageChanged() {
    if (_packageError == null) return;
    setState(() {
      _packageError = PackageNameRules.validate(
        _packageController.text.trim(),
        originalPackage: _info?.packageName,
      );
    });
  }

  Future<void> _load() async {
    try {
      final info = await _service.inspectApk(widget.sourcePath);
      if (!mounted) return;
      setState(() {
        _info = info;
        _nameController.text = widget.initialLabel != null &&
                widget.initialLabel!.trim().isNotEmpty
            ? '${widget.initialLabel!.trim()} Clone'
            : 'Clone ${info.packageName.split('.').last}';
        _packageController.text = info.suggestedPackage;
      });
    } on CloneFailure catch (e) {
      if (!mounted) return;
      setState(() => _loadError = e.message);
    }
  }

  void _applyAutoSuffix(bool value) {
    setState(() => _autoSuffix = value);
    final info = _info;
    if (info == null) return;
    _packageController.text =
        PackageNameRules.suggest(info.packageName, value ? 'clone' : 'ku');
  }

  Future<void> _start() async {
    final info = _info;
    if (info == null || _starting) return;

    final pkg = _packageController.text.trim();
    final error =
        PackageNameRules.validate(pkg, originalPackage: info.packageName);
    if (error != null) {
      setState(() => _packageError = error);
      _focusNode.requestFocus();
      return;
    }

    setState(() {
      _packageError = null;
      _starting = true;
    });

    final label = _nameController.text.trim();
    final result = await Navigator.of(context).push<CloneResult>(
      CupertinoPageRoute<CloneResult>(
        fullscreenDialog: true,
        builder: (_) => ProgressScreen(
          sourcePath: widget.sourcePath,
          newPackage: pkg,
          newLabel: label.isEmpty ? null : label,
          deepScan: _deepScan,
        ),
      ),
    );

    if (!mounted) return;
    setState(() => _starting = false);
    if (result != null) {
      Navigator.of(context).pop(); // tutup lembar pengaturan
    }
  }

  @override
  Widget build(BuildContext context) {
    final info = _info;
    return Container(
      decoration: BoxDecoration(
        color: CupertinoTheme.of(context).scaffoldBackgroundColor,
        borderRadius: const BorderRadius.vertical(top: Radius.circular(16)),
      ),
      child: SafeArea(
        top: false,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const SizedBox(height: 10),
              Container(
                width: 36,
                height: 5,
                decoration: BoxDecoration(
                  color: CupertinoColors.systemGrey3.resolveFrom(context),
                  borderRadius: BorderRadius.circular(3),
                ),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 14, 20, 4),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        'Atur Clone',
                        style: CupertinoTheme.of(context)
                            .textTheme
                            .navTitleTextStyle
                            .copyWith(fontWeight: FontWeight.w700),
                      ),
                    ),
                    GestureDetector(
                      onTap: () => Navigator.of(context).pop(),
                      child: const Text(
                        'Batal',
                        style: TextStyle(fontSize: 17, color: Color(0xFF0A84FF)),
                      ),
                    ),
                  ],
                ),
              ),
              if (_loadError != null)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 12, 20, 0),
                  child: _InlineError(message: _loadError!),
                )
              else if (info == null)
                const Padding(
                  padding: EdgeInsets.symmetric(vertical: 40),
                  child: CupertinoActivityIndicator(radius: 13),
                )
              else ...[
                IosGroup(
                  header: 'APK sumber',
                  children: [
                    IosRow(
                      title: _fileName(widget.sourcePath),
                      subtitle: info.packageName,
                      dense: true,
                    ),
                    IosRow(
                      title: 'Versi ${info.versionName}',
                      subtitle:
                          '${formatBytes(info.sizeBytes)} · ${info.permissions.length} izin',
                      dense: true,
                    ),
                  ],
                ),
                IosGroup(
                  header: 'Identitas baru',
                  children: [
                    CupertinoTextFormFieldRow(
                      controller: _nameController,
                      prefix: const _Prefix('Nama'),
                      placeholder: 'Nama tampilan clone',
                      textInputAction: TextInputAction.next,
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    CupertinoTextFormFieldRow(
                      controller: _packageController,
                      focusNode: _focusNode,
                      prefix: const _Prefix('Package'),
                      placeholder: 'com.contoh.clone',
                      keyboardType: TextInputType.url,
                      autocorrect: false,
                      enableSuggestions: false,
                      textInputAction: TextInputAction.done,
                      onSubmitted: (_) => _start(),
                      padding: const EdgeInsets.symmetric(vertical: 12),
                    ),
                    IosRow(
                      title: 'Akhiran .clone otomatis',
                      subtitle: 'Matikan untuk menulis package sendiri',
                      trailing: CupertinoSwitch(
                        value: _autoSuffix,
                        onChanged: _applyAutoSuffix,
                      ),
                    ),
                  ],
                  footer: _packageError == null
                      ? 'Package baru menentukan identitas aplikasi. Ubah bagian ini '
                          'saja sudah cukup agar clone bisa dipasang berdampingan '
                          'dengan aplikasi aslinya.'
                      : null,
                ),
                if (_packageError != null)
                  Padding(
                    padding: const EdgeInsets.fromLTRB(32, 8, 32, 0),
                    child: _InlineError(message: _packageError!),
                  ),
                IosGroup(
                  header: 'Lanjutan',
                  children: [
                    IosRow(
                      title: 'Ganti referensi package menyeluruh',
                      subtitle: 'Ikut mengubah nilai meta-data berawalan package asli',
                      trailing: CupertinoSwitch(
                        value: _deepScan,
                        onChanged: (v) => setState(() => _deepScan = v),
                      ),
                    ),
                  ],
                  footer: 'Biarkan mati bila aplikasi memakai layanan seperti Firebase '
                      'atau Google Maps: nilai konfigurasi itu sebaiknya tidak diubah.',
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(16, 22, 16, 18),
                  child: IosPrimaryButton(
                    label: _starting ? 'Memulai...' : 'Mulai Clone',
                    onPressed: _starting ? null : _start,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  static String _fileName(String path) {
    final i = path.lastIndexOf('/');
    return i < 0 ? path : path.substring(i + 1);
  }
}

class _Prefix extends StatelessWidget {
  const _Prefix(this.label);

  final String label;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 78,
      child: Text(
        label,
        style: TextStyle(
          fontSize: 17,
          letterSpacing: -0.41,
          color: CupertinoTheme.of(context).textTheme.textStyle.color,
        ),
      ),
    );
  }
}

class _InlineError extends StatelessWidget {
  const _InlineError({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const Padding(
          padding: EdgeInsets.only(top: 1),
          child: Icon(CupertinoIcons.exclamationmark_circle_fill,
              size: 15, color: CupertinoColors.systemRed),
        ),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            message,
            style: const TextStyle(
              fontSize: 13,
              height: 1.3,
              color: CupertinoColors.systemRed,
            ),
          ),
        ),
      ],
    );
  }
}
