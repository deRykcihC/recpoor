import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:path_provider/path_provider.dart';
import 'package:video_thumbnail/video_thumbnail.dart';
import 'package:open_filex/open_filex.dart';
import 'package:intl/intl.dart';
import 'package:path/path.dart' as p;
import 'dart:io';
import 'dart:convert';
import 'package:android_intent_plus/android_intent.dart';
import 'package:android_intent_plus/flag.dart';
import 'dart:async';
import 'package:share_plus/share_plus.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:dynamic_color/dynamic_color.dart';
import 'package:url_launcher/url_launcher.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return DynamicColorBuilder(
      builder: (ColorScheme? lightDynamic, ColorScheme? darkDynamic) {
        ColorScheme colorScheme;

        if (lightDynamic != null) {
          // Use system dynamic color but force explicit white/light background
          colorScheme = lightDynamic.copyWith(
            brightness: Brightness.light,
            surface: const Color(0xFFFBFAF8),
            onSurface: const Color(0xFF1C1B18),
            secondaryContainer: lightDynamic.secondaryContainer.withOpacity(0.5), // Adjust for consistency
          );
        } else {
          // Fallback
          colorScheme = ColorScheme.fromSeed(
            seedColor: const Color(0xFFD2B48C),
            brightness: Brightness.light,
            surface: const Color(0xFFFBFAF8),
            onSurface: const Color(0xFF1C1B18),
            primary: const Color(0xFF8B7355),
            secondaryContainer: const Color(0xFFEFEBE4),
          );
        }

        return MaterialApp(
          title: 'RecPoor',
          debugShowCheckedModeBanner: false,
          theme: ThemeData(
            colorScheme: colorScheme,
            useMaterial3: true,
            textTheme: GoogleFonts.notoSansTextTheme().copyWith(
              labelLarge: GoogleFonts.notoSans(fontWeight: FontWeight.w500),
              labelMedium: GoogleFonts.notoSans(fontWeight: FontWeight.w400),
              labelSmall: GoogleFonts.notoSans(fontWeight: FontWeight.w400),
              bodyLarge: GoogleFonts.notoSans(fontWeight: FontWeight.w400),
              bodyMedium: GoogleFonts.notoSans(fontWeight: FontWeight.w400),
              bodySmall: GoogleFonts.notoSans(fontWeight: FontWeight.w400),
            ),
            navigationBarTheme: NavigationBarThemeData(
              labelTextStyle: WidgetStateProperty.all(
                GoogleFonts.notoSans(
                  fontSize: 12,
                  fontWeight: FontWeight.w500,
                ),
              ),
            ),
          ),
          home: const MainScaffold(),
        );
      },
    );
  }
}

class MainScaffold extends StatefulWidget {
  const MainScaffold({super.key});

  @override
  State<MainScaffold> createState() => _MainScaffoldState();
}

class _MainScaffoldState extends State<MainScaffold> {
  int _selectedIndex = 0;
  late final PageController _pageController;

  @override
  void initState() {
    super.initState();
    _pageController = PageController(initialPage: _selectedIndex);
    _loadUIState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _checkFirstTimeGuide());
  }

  OverlayEntry? _overlayEntry;
  int _onboardingStep = 0; // 0: None, 1: Hiding, 2: Showing

  Future<void> _checkFirstTimeGuide() async {
    final prefs = await SharedPreferences.getInstance();
    final bool guideShown = prefs.getBool('guide_hidden_ui_shown_v2') ?? false;

    if (!guideShown && mounted) {
      await Future.delayed(const Duration(seconds: 1));
      if (!mounted) return;
      
      setState(() {
        _onboardingStep = 1;
      });
      _showOverlay("Long press 'Record' to hide the header UI");
    }
  }

  void _showOverlay(String text, {bool autoDismiss = false}) {
    _removeOverlay();
    _overlayEntry = OverlayEntry(
      builder: (context) => Stack(
        children: [
          // Block touches on the entire screen background
          Positioned.fill(
            child: GestureDetector(
              onTap: () {}, // Absorb taps
              behavior: HitTestBehavior.opaque,
              child: Container(color: Colors.black.withOpacity(0.5)), // Dim background
            ),
          ),
          Positioned(
            bottom: 80, // Position above nav bar
            left: 0,
            right: 0,
            child: Material(
              color: Colors.transparent,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start, // Allow manual positioning of arrow
                children: [
                  Center(
                    child: Container(
                      padding: const EdgeInsets.all(16),
                      margin: const EdgeInsets.symmetric(horizontal: 32),
                      decoration: BoxDecoration(
                        color: Theme.of(context).colorScheme.inverseSurface,
                        borderRadius: BorderRadius.circular(12),
                      ),
                      child: Text(
                        text,
                        style: TextStyle(color: Theme.of(context).colorScheme.onInverseSurface),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
                  const SizedBox(height: 8),
                  // Align arrow to the first tab (25% of screen width)
                  Padding(
                    padding: EdgeInsets.only(left: MediaQuery.of(context).size.width * 0.25 - 16),
                    child: Icon(Icons.arrow_downward, color: Theme.of(context).colorScheme.inverseSurface, size: 32),
                  ),
                ],
              ),
            ),
          ),
          // We need to let the BottomNavigationBar receive events.
          // Since the overlay is on top, we usually can't.
          // However, we can make the Overlay PASS THROUGH events in the specific region of the button.
          // But that's hard with standard Flutter widgets without a custom RenderBox.
          // ALTERNATIVE: Don't use a full-screen blocking overlay.
          // Use AbsorbPointer on the Scaffold body (in build method) and ignore clicks on the other tab.
          // For this specific request "user has to complete it", the dimming background is nice but blocking taps is key.
          // NOTE: If I use a full screen stack in Overlay, it BLOCKS the bottom nav bar too!
          // So I will NOT use the full screen blocker here, but rely on AbsorbPointer in the Scaffold body + logic in _changeTab.
        ],
      ),
    );
     
    // Use the transparent version correctly
     _overlayEntry = OverlayEntry(
      builder: (context) => Positioned(
        bottom: 80,
        left: 0,
        right: 0,
        child: Material(
          color: Colors.transparent,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Center(
                 child: Container(
                  padding: const EdgeInsets.all(16),
                  margin: const EdgeInsets.symmetric(horizontal: 32),
                  decoration: BoxDecoration(
                    color: Theme.of(context).colorScheme.inverseSurface,
                    borderRadius: BorderRadius.circular(12),
                  ),
                  child: Text(
                    text,
                    style: TextStyle(color: Theme.of(context).colorScheme.onInverseSurface),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
              const SizedBox(height: 8),
              Padding(
                padding: EdgeInsets.only(left: MediaQuery.of(context).size.width * 0.25 - 16),
                 child: Icon(Icons.arrow_downward, color: Theme.of(context).colorScheme.inverseSurface, size: 32),
              ),
            ],
          ),
        ),
      ),
    );
    
    Overlay.of(context).insert(_overlayEntry!);
    
    if (autoDismiss) {
      Future.delayed(const Duration(seconds: 2), () {
        if (mounted) _removeOverlay();
      });
    }
  }

  void _removeOverlay() {
    _overlayEntry?.remove();
    _overlayEntry = null;
  }

  Future<void> _loadUIState() async {
    final prefs = await SharedPreferences.getInstance();
    if (mounted) {
      setState(() {
        _showUI = prefs.getBool('show_header_ui') ?? true;
      });
    }
  }
  
  Future<void> _toggleUI() async {
    HapticFeedback.mediumImpact();
    
    // Onboarding Logic
    if (_onboardingStep == 1) {
       // User just hid it (supposedly)
       setState(() {
        _showUI = !_showUI; // Actually optimize the toggle
        _onboardingStep = 2;
       });
       _showOverlay("Great! Now long press again to bring it back.");
    } else if (_onboardingStep == 2) {
      // User unhid it, finish
      setState(() {
        _showUI = !_showUI;
        _onboardingStep = 0;
      });
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('guide_hidden_ui_shown_v2', true);
      
      _showOverlay("You're all set! Long press anytime to toggle.", autoDismiss: true);
    } else {
      // Normal usage
      setState(() {
        _showUI = !_showUI;
      });
    }

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('show_header_ui', _showUI);
  }

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  bool _showUI = true;

  void _changeTab(int index) {
    if (_onboardingStep > 0) return; // Block tab switching during onboarding

    setState(() {
      _selectedIndex = index;
    });
    _pageController.animateToPage(
      index,
      duration: const Duration(milliseconds: 300),
      curve: Curves.easeInOut,
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: AbsorbPointer(
        absorbing: _onboardingStep > 0,
        child: PageView(
          controller: _pageController,
          physics: const NeverScrollableScrollPhysics(), // Prevent swiping to ensure navigation via bar
          children: [
            ScreenRecordPage(onTabChange: _changeTab, showUI: _showUI),
            const FilesPage(),
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        onDestinationSelected: _changeTab,
        selectedIndex: _selectedIndex,
        backgroundColor: Theme.of(context).colorScheme.surface,
        elevation: 0,
        destinations: <Widget>[
          NavigationDestination(
            selectedIcon: GestureDetector(
              onLongPress: _toggleUI,
              child: const Icon(Icons.videocam_rounded),
            ),
            icon: GestureDetector(
               onLongPress: _toggleUI,
              child: const Icon(Icons.videocam_outlined),
            ),
            label: 'Record',
          ),
          const NavigationDestination( // Wrap 2nd tab to allow blocking? _changeTab already blocks it.
            selectedIcon: Icon(Icons.folder_open_rounded),
            icon: Icon(Icons.folder_open_outlined),
            label: 'Recordings',
          ),
        ],
      ),
    );
  }
}

class ScreenRecordPage extends StatefulWidget {
  final Function(int)? onTabChange;
  final bool showUI;
  const ScreenRecordPage({super.key, this.onTabChange, this.showUI = true});

  @override
  State<ScreenRecordPage> createState() => _ScreenRecordPageState();
}

class _ScreenRecordPageState extends State<ScreenRecordPage> with WidgetsBindingObserver, AutomaticKeepAliveClientMixin {
  static const platform = MethodChannel('com.deryk.recpoor/screen_record');
  bool _isRecording = false;
  Timer? _timer;
  int _recordDuration = 0;
  int _selectedBitrate = 8000000;

  final Map<int, String> _bitrateOptions = {
    1000000: '1 Mbps',
    2000000: '2 Mbps',
    4000000: '4 Mbps',
    6000000: '6 Mbps',
    8000000: '8 Mbps',
    12000000: '12 Mbps',
    16000000: '16 Mbps',
  };

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    platform.setMethodCallHandler(_handleMethodCall);
    _checkRecordingStatus();
    _checkPendingNavigation();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _checkRecordingStatus();
      _checkPendingNavigation();
    }
  }

  Future<void> _checkPendingNavigation() async {
    try {
      final String? nav = await platform.invokeMethod('getPendingNavigation');
      if (nav == 'recordings' && widget.onTabChange != null) {
        widget.onTabChange!(1);
      }
    } catch (e) {
      // Ignore
    }
  }

  Future<void> _checkRecordingStatus() async {
    try {
      final bool isRecording = await platform.invokeMethod('getRecordingStatus');
      if (isRecording && !_isRecording) {
        setState(() {
          _isRecording = true;
        });
        _startTimer();
      } else if (!isRecording && _isRecording) {
        setState(() {
          _isRecording = false;
        });
        _stopTimer();
      }
    } on PlatformException catch (e) {
      debugPrint("Failed to get recording status: '${e.message}'.");
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _timer?.cancel();
    super.dispose();
  }
  
  Future<void> _handleMethodCall(MethodCall call) async {
    if (call.method == 'recordingStopped') {
      setState(() {
        _isRecording = false;
      });
      _stopTimer();
    } else if (call.method == 'recordingStarted') {
       setState(() {
        _isRecording = true;
      });
      _startTimer();
    } else if (call.method == 'navigateToRecordings') {
      if (widget.onTabChange != null) {
        widget.onTabChange!(1);
      }
    }
  }

  void _startTimer() {
    _recordDuration = 0;
    _timer?.cancel();
    _timer = Timer.periodic(const Duration(seconds: 1), (Timer t) {
      setState(() {
        _recordDuration++;
      });
    });
  }

  void _stopTimer() {
    _timer?.cancel();
    _recordDuration = 0;
  }

  String _formatDuration(int seconds) {
    if (seconds > 5999) return '💩';
    final int minutes = seconds ~/ 60;
    final int remainingSeconds = seconds % 60;
    return '${minutes.toString().padLeft(2, '0')}:${remainingSeconds.toString().padLeft(2, '0')}';
  }

  Future<void> _startRecording() async {
    Map<Permission, PermissionStatus> statuses = await [
      Permission.microphone,
      Permission.notification,
    ].request();

    if (statuses[Permission.microphone] != PermissionStatus.granted) return;

    try {
      await platform.invokeMethod('startRecording', {'bitrate': _selectedBitrate});
    } on PlatformException catch (e) {
      debugPrint("Failed to start: ${e.message}");
    }
  }

  Future<void> _stopRecording() async {
    try {
      await platform.invokeMethod('stopRecording');
      setState(() {
        _isRecording = false;
      });
      _stopTimer();
    } on PlatformException catch (e) {
      debugPrint("Failed to stop recording: '${e.message}'.");
    }
  }

  void _showBitratePicker() {
    showModalBottomSheet(
      context: context,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(28)),
      ),
      builder: (context) {
        return SafeArea(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Padding(
                padding: const EdgeInsets.all(16.0),
                child: Text('Video Bitrate', style: Theme.of(context).textTheme.titleMedium),
              ),
              Flexible(
                child: ListView(
                  shrinkWrap: true,
                  children: _bitrateOptions.entries.map((entry) {
                    return ListTile(
                      title: Text(entry.value),
                      trailing: _selectedBitrate == entry.key ? const Icon(Icons.check) : null,
                      onTap: () {
                        setState(() => _selectedBitrate = entry.key);
                        Navigator.pop(context);
                      },
                    );
                  }).toList(),
                ),
              ),
            ],
          ),
        );
      },
    );
  }

  @override
  bool get wantKeepAlive => true;

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final colorScheme = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;

    return SafeArea(
      child: Column(
        children: [
          AnimatedSwitcher(
            duration: const Duration(milliseconds: 300),
            child: widget.showUI ? Column(
              children: [
                Padding(
                  padding: const EdgeInsets.only(top: 16.0, bottom: 8.0),
                  child: Material( // Wrap with Material to support InkWell splash on any background
                    color: Colors.transparent,
                    child: InkWell(
                      onTap: () async {
                         final Uri url = Uri.parse('https://buymeacoffee.com/derykcihc');
                         if (!await launchUrl(url, mode: LaunchMode.externalApplication)) {
                           debugPrint('Could not launch $url');
                         }
                      },
                      borderRadius: BorderRadius.circular(30),
                      child: Container(
                        padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 12),
                        decoration: BoxDecoration(
                          color: const Color(0xFFFFDD00),
                          borderRadius: BorderRadius.circular(30),
                          boxShadow: [
                            BoxShadow(
                              color: Colors.black.withOpacity(0.1),
                              blurRadius: 8,
                              offset: const Offset(0, 4),
                            ),
                          ],
                        ),
                        child: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            const Icon(Icons.coffee_rounded, color: Colors.black),
                            const SizedBox(width: 8),
                            // Cookie font might not be available if not added to pubspec, fallback to something safe like pacifico or just bold
                            Text( 
                              'Buy me a coffee',
                              style: GoogleFonts.cookie(
                                 fontSize: 22,
                                 fontWeight: FontWeight.bold,
                                 color: Colors.black,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ),
                ),
                
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 32.0, vertical: 12.0),
                  child: Column(
                    children: [
                      SelectableText(
                        "RecPoor is designed for devices without a native screen recorder, featuring internal audio recording without Root. Unlike other apps, it is free from bloatware and ads. If you appreciate the idea and want to help keep the app available on the Play Store, you can donate via Buy Me a Coffee. Otherwise, it's always free on GitHub.",
                        textAlign: TextAlign.center,
                        style: textTheme.bodySmall?.copyWith(
                          color: colorScheme.onSurfaceVariant,
                          height: 1.4,
                        ),
                      ),
                      const SizedBox(height: 12),
                      const SizedBox(height: 12),
                      Wrap(
                        alignment: WrapAlignment.center,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          Text(
                            "Have bugs, feedback or suggestions? Leave it in the ",
                            textAlign: TextAlign.center,
                            style: textTheme.bodySmall?.copyWith(
                              color: colorScheme.onSurfaceVariant,
                            ),
                          ),
                          InkWell(
                            borderRadius: BorderRadius.circular(8),
                            onTap: () => launchUrl(Uri.parse('https://github.com/deRykcihC/recpoor'), mode: LaunchMode.externalApplication),
                            child: Padding(
                              padding: const EdgeInsets.symmetric(horizontal: 4.0, vertical: 2.0),
                              child: Text(
                                "GitHub repository",
                                textAlign: TextAlign.center,
                                style: textTheme.bodySmall?.copyWith(
                                  color: colorScheme.primary,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      InkWell(
                        borderRadius: BorderRadius.circular(8),
                        onTap: () => launchUrl(Uri.parse('https://github.com/deRykcihC/recpoor/blob/main/PRIVACY_POLICY.md'), mode: LaunchMode.externalApplication),
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 8.0, vertical: 4.0),
                          child: Text(
                            "Privacy Policy",
                            textAlign: TextAlign.center,
                            style: textTheme.bodySmall?.copyWith(
                              color: colorScheme.primary,
                              fontWeight: FontWeight.bold,
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ],
            ) : const SizedBox(height: 24), // Maintain a little spacing or shrink
          ),
          
          const Spacer(),
          
          // 1. Timer indicator - Above controls
          Container(
            width: double.infinity,
            padding: const EdgeInsets.symmetric(vertical: 24),
            margin: const EdgeInsets.only(left: 24, right: 24, top: 4, bottom: 8),
            decoration: BoxDecoration(
              color: _isRecording ? colorScheme.errorContainer : colorScheme.secondaryContainer,
              borderRadius: BorderRadius.circular(28),
            ),
            child: Center(
              child: Text(
                _isRecording ? _formatDuration(_recordDuration) : "00:00",
                style: textTheme.displayLarge?.copyWith(
                  color: _isRecording ? colorScheme.error : colorScheme.primary,
                  fontWeight: FontWeight.bold,
                  letterSpacing: -2,
                  fontSize: 80,
                ),
              ),
            ),
          ),

          // 2. Main Control Row
          Padding(
            padding: const EdgeInsets.fromLTRB(24, 8, 24, 24),
            child: Row(
              children: [
                // Start/Stop Button
                Expanded(
                  flex: 2,
                  child: SizedBox(
                    height: 80,
                    child: FilledButton(
                      onPressed: _isRecording ? _stopRecording : _startRecording,
                      style: FilledButton.styleFrom(
                        backgroundColor: _isRecording ? colorScheme.error : colorScheme.primary,
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                      ),
                      child: Icon(
                        _isRecording ? Icons.stop_rounded : Icons.fiber_manual_record_rounded, 
                        size: 36,
                      ),
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                // Bitrate Selector
                Expanded(
                  flex: 3,
                  child: SizedBox(
                    height: 80,
                    child: OutlinedButton.icon(
                      onPressed: _isRecording ? null : _showBitratePicker,
                      style: OutlinedButton.styleFrom(
                        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(24)),
                        side: BorderSide(color: colorScheme.outline.withOpacity(0.5)),
                      ),
                      icon: const Icon(Icons.settings_input_component_rounded, size: 28),
                      label: Text(
                        _bitrateOptions[_selectedBitrate] ?? "",
                        style: const TextStyle(
                          fontWeight: FontWeight.bold,
                          fontSize: 18,
                        ),
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class FilesPage extends StatefulWidget {
  const FilesPage({super.key});

  @override
  State<FilesPage> createState() => _FilesPageState();
}

class _FilesPageState extends State<FilesPage> with AutomaticKeepAliveClientMixin {
  static const platform = MethodChannel('com.deryk.recpoor/screen_record');
  List<FileSystemEntity> _files = [];
  bool _isLoading = true;
  final Map<String, String?> _thumbnails = {};
  final Map<String, Map<String, dynamic>?> _videoMetaMap = {};

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _loadThumbnailCache().then((_) => _loadFiles());
  }

  Future<void> _loadThumbnailCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final String? cachedData = prefs.getString('thumbnail_paths');
      if (cachedData != null) {
        final Map<String, dynamic> decoded = json.decode(cachedData);
        if (mounted) {
          setState(() {
            _thumbnails.addAll(decoded.cast<String, String>());
          });
        }
      }
    } catch (e) {
      debugPrint("Error loading thumbnail cache: $e");
    }
  }

  Future<void> _saveThumbnailCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('thumbnail_paths', json.encode(_thumbnails));
    } catch (e) {
      debugPrint("Error saving thumbnail cache: $e");
    }
  }

  Future<void> _loadFiles() async {
    if (mounted) setState(() => _isLoading = true);
    try {
      final Directory? downloadsDir = Directory('/storage/emulated/0/Download/RecPoor');
      
      if (await downloadsDir?.exists() ?? false) {
        final List<FileSystemEntity> files = downloadsDir!
            .listSync()
            .where((file) {
              if (file is! File) return false;
              final String name = p.basename(file.path);
              return name.endsWith('.mp4') && !name.startsWith('.');
            })
            .toList();
        
        // Sort by date (newest first)
        files.sort((a, b) => (b as File).lastModifiedSync().compareTo((a as File).lastModifiedSync()));
        
        if (mounted) {
          setState(() {
            _files = files;
            _isLoading = false;
          });
        }

        // Load metadata and thumbnails
        for (var file in files) {
          _generateThumbnail(file.path);
          _loadVideoInfo(file.path);
        }
      } else {
        if (mounted) {
          setState(() {
            _files = [];
            _isLoading = false;
          });
        }
      }
    } catch (e) {
      debugPrint("Error loading files: $e");
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _loadVideoInfo(String path) async {
    if (_videoMetaMap.containsKey(path) && _videoMetaMap[path] != null) return;
    try {
      final  Map<Object?, Object?>? meta = await platform.invokeMethod('getVideoMeta', {'path': path});
      if (meta != null && mounted) {
        setState(() {
          _videoMetaMap[path] = meta.cast<String, dynamic>();
        });
      }
    } catch (e) {
      debugPrint("Error loading video info for $path: $e");
    }
  }

  Future<void> _generateThumbnail(String path) async {
    // Check memory cache first and verify file exists on disk
    if (_thumbnails.containsKey(path) && _thumbnails[path] != null) {
      if (await File(_thumbnails[path]!).exists()) {
        return;
      } else {
        // Path in cache is invalid, remove it
        _thumbnails.remove(path);
      }
    }

    try {
      final supportDir = await getApplicationSupportDirectory();
      final thumbDir = Directory('${supportDir.path}/thumbnails');
      if (!await thumbDir.exists()) await thumbDir.create(recursive: true);

      final String? result = await VideoThumbnail.thumbnailFile(
        video: path,
        thumbnailPath: thumbDir.path,
        imageFormat: ImageFormat.JPEG,
        maxWidth: 128,
        quality: 50,
      );

      if (result != null && mounted) {
        setState(() {
          _thumbnails[path] = result;
        });
        _saveThumbnailCache();
      }
    } catch (e) {
      debugPrint("Error generating thumbnail: $e");
    }
  }

  String _getFileSize(File file) {
    try {
       final bytes = file.lengthSync();
       if (bytes < 1024) return '$bytes B';
       if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
       return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    } catch (e) {
       return 'Unknown';
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final colorScheme = Theme.of(context).colorScheme;

    return Scaffold(
      appBar: AppBar(
        title: Text('Recordings', style: GoogleFonts.notoSans(fontWeight: FontWeight.bold)),
        backgroundColor: Colors.transparent,
        elevation: 0,
      ),
      body: LayoutBuilder(

        builder: (context, constraints) {
          final textTheme = Theme.of(context).textTheme;
          // Calculate how many items fit on the screen
          const double itemHeight = 100.0;
          final int maxItems = (constraints.maxHeight / itemHeight).floor();
          
          final bool hasMore = _files.length > maxItems;
          // Show full maxItems, append the text at the bottom
          final int displayLimit = maxItems;
          final displayFiles = _files.take(displayLimit).toList();
          final int itemCount = displayFiles.length + (hasMore ? 1 : 0);

          return RefreshIndicator(
            onRefresh: _loadFiles,
            child: _isLoading
              ? const Center(child: CircularProgressIndicator())
              : _files.isEmpty
                  ? ListView(
                      physics: const AlwaysScrollableScrollPhysics(),
                      children: [
                        SizedBox(
                          height: constraints.maxHeight * 0.7,
                          child: Column(
                            mainAxisAlignment: MainAxisAlignment.center,
                            children: [
                              Icon(Icons.folder_open, size: 64, color: colorScheme.outline.withOpacity(0.5)),
                              const SizedBox(height: 16),
                              Text("No recordings found", style: TextStyle(color: colorScheme.outline)),
                            ],
                          ),
                        ),
                      ],
                    )
                  : ListView.builder(
                      physics: const AlwaysScrollableScrollPhysics(),
                      padding: EdgeInsets.fromLTRB(16, 16, 16, hasMore ? 4 : 16),
                      itemCount: itemCount,
                      itemBuilder: (context, index) {
                        // Check if this is the "More" hint text at the bottom
                        if (hasMore && index == displayFiles.length) {
                          final remaining = _files.length - displayFiles.length;
                          return Padding(
                            padding: const EdgeInsets.only(bottom: 2),
                            child: Center(
                              child: Text(
                                "... and $remaining more",
                                style: textTheme.bodySmall?.copyWith(
                                  color: colorScheme.outline,
                                  fontWeight: FontWeight.bold,
                                  fontSize: 10, 
                                ),
                              ),
                            ),
                          );
                        }

                        // Normal File Card
                        final file = displayFiles[index] as File;
                        final thumbnail = _thumbnails[file.path];
                        final meta = _videoMetaMap[file.path];
                        final fps = meta?['fps'] as double?;
                        
                        final date = DateFormat('yyyy-MM-dd HH:mm').format(file.lastModifiedSync());
                        
                        return SizedBox(
                          height: 100,
                          child: Card(
                            margin: const EdgeInsets.only(bottom: 12),
                            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(20)),
                            color: colorScheme.secondaryContainer.withOpacity(0.5),
                            elevation: 0,
                            clipBehavior: Clip.antiAlias,
                            child: InkWell(
                              borderRadius: BorderRadius.circular(20),
                              onTap: () async {
                                await OpenFilex.open(file.path);
                              },
                              onLongPress: () {
                                HapticFeedback.mediumImpact();
                                Share.shareXFiles([XFile(file.path)]);
                              },
                              child: Center(
                                child: ListTile(
                                  contentPadding: const EdgeInsets.symmetric(horizontal: 12),
                                  leading: ClipRRect(
                                    borderRadius: BorderRadius.circular(12),
                                    child: Container(
                                      width: 80,
                                      height: 60,
                                      color: colorScheme.surfaceVariant,
                                      child: thumbnail != null
                                          ? Image.file(File(thumbnail), fit: BoxFit.cover)
                                          : const Icon(Icons.video_library),
                                    ),
                                  ),
                                  title: Text(
                                    date,
                                    style: textTheme.bodyMedium?.copyWith(fontWeight: FontWeight.w500),
                                  ),
                                  subtitle: Row(
                                    children: [
                                      Text(_getFileSize(file), style: textTheme.bodyMedium?.copyWith(color: colorScheme.outline)),
                                      if (fps != null) ...[
                                         const SizedBox(width: 8),
                                         Text("${fps.toStringAsFixed(0)} FPS", style: textTheme.bodyMedium?.copyWith(color: colorScheme.outline)),
                                      ],
                                    ],
                                  ),
                                  trailing: IconButton(
                                    icon: const Icon(Icons.delete_outline),
                                    onPressed: () {
                                       showDialog(
                                         context: context,
                                         builder: (context) => AlertDialog(
                                           backgroundColor: colorScheme.surface,
                                           shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(28)),
                                           title: Text("Delete recording?", style: TextStyle(color: colorScheme.onSurface, fontWeight: FontWeight.bold)),
                                           content: Text("This action cannot be undone.", style: TextStyle(color: colorScheme.onSurfaceVariant)),
                                           actions: [
                                             TextButton(
                                               onPressed: () => Navigator.pop(context), 
                                               child: Text("Cancel", style: TextStyle(color: colorScheme.primary))
                                             ),
                                             FilledButton(
                                               style: FilledButton.styleFrom(
                                                 backgroundColor: colorScheme.error,
                                                 foregroundColor: colorScheme.onError,
                                               ),
                                               onPressed: () {
                                                 file.deleteSync();
                                                 Navigator.pop(context);
                                                 _loadFiles();
                                               },
                                               child: const Text("Delete"),
                                             ),
                                           ],
                                         ),
                                       );
                                    },
                                  ),
                                ),
                              ),
                            ),
                          ),
                        );
                      },
                    ),
          );
        },
      ),
    );
  }
}
