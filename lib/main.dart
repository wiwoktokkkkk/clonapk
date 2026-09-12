import 'package:flutter/cupertino.dart';

import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const ClonApkApp());
}

/// Aplikasi pembuat salinan APK dengan tampilan bergaya iOS.
///
/// Seluruh UI memakai widget Cupertino (bukan Material) supaya perilaku navigasi,
/// tipografi, dan animasinya terasa seperti aplikasi iOS asli.
class ClonApkApp extends StatelessWidget {
  const ClonApkApp({super.key});

  @override
  Widget build(BuildContext context) {
    return CupertinoApp(
      title: 'ClonApk',
      debugShowCheckedModeBanner: false,
      // brightness dibiarkan null: widget Cupertino mewarisi mode
      // terang/gelap dari platform, dan warna dinamis menyesuaikan sendiri.
      theme: const CupertinoThemeData(
        primaryColor: Color(0xFF0A84FF),
        scaffoldBackgroundColor: CupertinoColors.systemGroupedBackground,
        textTheme: CupertinoTextThemeData(
          navLargeTitleTextStyle: TextStyle(
            fontSize: 34,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.37,
            color: CupertinoColors.label,
          ),
        ),
      ),
      home: const HomeScreen(),
    );
  }
}
