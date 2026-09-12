import 'dart:async';

import 'package:flutter/cupertino.dart';

import '../models/clone_result.dart';
import '../services/clone_service.dart';
import '../widgets/ios_widgets.dart';

/// Layar proses clone: menampilkan tahap yang sedang berjalan dan persentasenya.
///
/// Layar ini memulai pekerjaannya sendiri saat dibuat, sehingga hasil bisa
/// dikembalikan lewat [Navigator.pop] dengan nilai [CloneResult].
class ProgressScreen extends StatefulWidget {
  const ProgressScreen({
    super.key,
    required this.sourcePath,
    required this.newPackage,
    this.newLabel,
    this.deepScan = false,
  });

  final String sourcePath;
  final String newPackage;
  final String? newLabel;
  final bool deepScan;

  @override
  State<ProgressScreen> createState() => _ProgressScreenState();
}

class _ProgressScreenState extends State<ProgressScreen>
    with SingleTickerProviderStateMixin {
  final _service = CloneService.instance;

  CloneProgress _progress = const CloneProgress('Menyiapkan', 0);
  String? _error;
  StreamSubscription<CloneProgress>? _sub;
  late final Stopwatch _watch = Stopwatch()..start();

  @override
  void initState() {
    super.initState();
    _sub = _service.progress.listen((p) {
      if (!mounted) return;
      setState(() => _progress = p);
    });
    _run();
  }

  @override
  void dispose() {
    _sub?.cancel();
    super.dispose();
  }

  Future<void> _run() async {
    try {
      final result = await _service.clone(
        sourcePath: widget.sourcePath,
        newPackage: widget.newPackage,
        newLabel: widget.newLabel,
        deepScan: widget.deepScan,
      );
      if (!mounted) return;
      Navigator.of(context).pop(result);
    } on CloneFailure catch (e) {
      if (!mounted) return;
      setState(() => _error = e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final elapsed = _watch.elapsed;
    return CupertinoPageScaffold(
      navigationBar: CupertinoNavigationBar(
        middle: Text(_error == null ? 'Sedang meng-clone' : 'Gagal'),
        leading: _error == null
            ? const SizedBox.shrink()
            : CupertinoNavigationBarBackButton(
                onPressed: () => Navigator.of(context).pop(),
              ),
        automaticallyImplyLeading: false,
      ),
      child: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (_error == null) ...[
                  _ProgressRing(fraction: _progress.fraction),
                  const SizedBox(height: 26),
                  Text(
                    '${_progress.percent}%',
                    style: const TextStyle(
                      fontSize: 34,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.37,
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    _progress.stage,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 15,
                      color: CupertinoColors.secondaryLabel.resolveFrom(context),
                    ),
                  ),
                  const SizedBox(height: 28),
                  Text(
                    widget.newPackage,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 13,
                      fontFamily: 'monospace',
                      color: CupertinoColors.tertiaryLabel.resolveFrom(context),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(
                    'Berjalan ${elapsed.inSeconds} detik',
                    style: TextStyle(
                      fontSize: 13,
                      color: CupertinoColors.tertiaryLabel.resolveFrom(context),
                    ),
                  ),
                ] else ...[
                  const Icon(CupertinoIcons.exclamationmark_triangle_fill,
                      size: 48, color: CupertinoColors.systemRed),
                  const SizedBox(height: 18),
                  const Text(
                    'Clone tidak bisa diselesaikan',
                    style: TextStyle(fontSize: 20, fontWeight: FontWeight.w700),
                  ),
                  const SizedBox(height: 10),
                  Text(
                    _error!,
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 15,
                      height: 1.4,
                      color: CupertinoColors.secondaryLabel.resolveFrom(context),
                    ),
                  ),
                  const SizedBox(height: 24),
                  IosPillButton(
                    label: 'Kembali',
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Cincin kemajuan bergaya iOS dengan animasi halus.
class _ProgressRing extends StatelessWidget {
  const _ProgressRing({required this.fraction});

  final double fraction;

  @override
  Widget build(BuildContext context) {
    final color = CupertinoTheme.of(context).primaryColor;
    return SizedBox(
      width: 120,
      height: 120,
      child: TweenAnimationBuilder<double>(
        tween: Tween<double>(begin: 0, end: fraction),
        duration: const Duration(milliseconds: 420),
        curve: Curves.easeOutCubic,
        builder: (context, value, _) => CustomPaint(
          painter: _RingPainter(
            fraction: value,
            color: color,
            trackColor: CupertinoColors.systemGrey5.resolveFrom(context),
          ),
        ),
      ),
    );
  }
}

class _RingPainter extends CustomPainter {
  const _RingPainter({
    required this.fraction,
    required this.color,
    required this.trackColor,
  });

  final double fraction;
  final Color color;
  final Color trackColor;

  @override
  void paint(Canvas canvas, Size size) {
    final center = size.center(Offset.zero);
    const strokeWidth = 10.0;
    final radius = (size.shortestSide - strokeWidth) / 2;

    final track = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..color = trackColor;
    canvas.drawCircle(center, radius, track);

    if (fraction <= 0) return;
    final arc = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = strokeWidth
      ..strokeCap = StrokeCap.round
      ..color = color;
    canvas.drawArc(
      Rect.fromCircle(center: center, radius: radius),
      -1.5707963267948966,
      6.283185307179586 * fraction,
      false,
      arc,
    );
  }

  @override
  bool shouldRepaint(_RingPainter old) =>
      old.fraction != fraction ||
      old.color != color ||
      old.trackColor != trackColor;
}
