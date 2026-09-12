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
      theme: const CupertinoThemeData(
        primaryColor: Color(0xFF0A84FF),
        brightness: Brightness.light,
        scaffoldBackgroundColor: Color(0xFFF2F2F7),
        textTheme: CupertinoTextThemeData(
          navLargeTitleTextStyle: TextStyle(
            fontFamily: '.SF Pro Display',
            fontSize: 34,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.37,
            color: CupertinoColors.black,
          ),
        ),
      ),
      darkTheme: const CupertinoThemeData(
        primaryColor: Color(0xFF0A84FF),
        brightness: Brightness.dark,
        scaffoldBackgroundColor: Color(0xFF000000),
        textTheme: CupertinoTextThemeData(
          navLargeTitleTextStyle: TextStyle(
            fontFamily: '.SF Pro Display',
            fontSize: 34,
            fontWeight: FontWeight.w700,
            letterSpacing: 0.37,
            color: CupertinoColors.white,
          ),
        ),
      ),
      localizationsDelegates: const <LocalizationsDelegate<dynamic>>[
        DefaultMaterialLocalizations.delegate,
        DefaultWidgetsLocalizations.delegate,
        DefaultCupertinoLocalizations.delegate,
      ],
      home: const HomeScreen(),
    );
  }
}
