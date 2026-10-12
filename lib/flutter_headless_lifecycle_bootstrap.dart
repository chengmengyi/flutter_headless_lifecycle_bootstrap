import 'dart:async';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Business initialization callback supplied by the host application.
typedef HeadlessLifecycleInitializer = FutureOr<void> Function();

/// Coordinates one long-lived Dart isolate with the Android lifecycle bootstrap
/// service and the Flutter activity supplied by this plugin.
///
/// The host application's Android entrypoint must be a top-level function in
/// `lib/main.dart`, annotated with `@pragma('vm:entry-point')`, and call [run].
/// Android executes that entrypoint whether the process is first created by the
/// exported lifecycle service or by the launcher activity.
class FlutterHeadlessLifecycleBootstrap {
  FlutterHeadlessLifecycleBootstrap._();

  static const String channelName =
      'flutter_headless_lifecycle_bootstrap/bootstrap';

  static const MethodChannel _channel = MethodChannel(channelName);

  static bool _hasStarted = false;
  static bool _backgroundInitializationCompleted = false;
  static bool _initialUiRequested = false;
  static bool _uiRequested = false;
  static bool _uiStarted = false;
  static Future<void>? _backgroundInitializationFuture;
  static Future<void>? _uiInitializationFuture;
  static HeadlessLifecycleInitializer? _uiInitializer;
  static final Completer<void> _keepAliveCompleter = Completer<void>();

  /// Starts the host application's background initialization and keeps the
  /// isolate alive for the lifetime of the Android process.
  ///
  /// [backgroundInitializer] is always run once. Put storage initialization,
  /// notification initialization with `requestPermission: false`, and Adjust
  /// initialization here.
  ///
  /// [uiInitializer] is run once, only after Android attaches the plugin's
  /// lifecycle Flutter activity. Put UI-only initialization and `runApp()`
  /// here.
  ///
  /// This future intentionally never completes. The Android side owns the
  /// single FlutterEngine and keeps it until the operating system terminates
  /// the process.
  static Future<void> run({
    required HeadlessLifecycleInitializer backgroundInitializer,
    required HeadlessLifecycleInitializer uiInitializer,
  }) async {
    WidgetsFlutterBinding.ensureInitialized();

    if (_hasStarted) {
      throw StateError(
        'FlutterHeadlessLifecycleBootstrap.run() may only be called once '
        'per Dart isolate.',
      );
    }
    _hasStarted = true;
    _uiInitializer = uiInitializer;

    _channel.setMethodCallHandler(_handleNativeMethodCall);

    final bool nativeUiRequested = await _notifyNativeBootstrapReady();
    _initialUiRequested = nativeUiRequested;
    if (nativeUiRequested) {
      _uiRequested = true;
    }

    _backgroundInitializationFuture = _initializeBackground(
      backgroundInitializer,
    );
    await _backgroundInitializationFuture;

    _backgroundInitializationCompleted = true;
    await _startUiIfNeeded();

    await _keepAliveCompleter.future;
  }

  static Future<dynamic> _handleNativeMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'requestUi':
        _uiRequested = true;
        await _startUiIfNeeded();
        return <String, Object?>{'accepted': true, 'uiStarted': _uiStarted};
      case 'getDartBootstrapState':
        return <String, Object?>{
          'hasStarted': _hasStarted,
          'backgroundInitializationCompleted':
              _backgroundInitializationCompleted,
          'initialUiRequested': _initialUiRequested,
          'uiRequested': _uiRequested,
          'uiStarted': _uiStarted,
        };
      default:
        throw MissingPluginException(
          'Unknown $channelName method: ${call.method}',
        );
    }
  }

  static Future<bool> _notifyNativeBootstrapReady() async {
    try {
      final Map<Object?, Object?>? state =
          await _channel.invokeMapMethod<Object?, Object?>('bootstrapReady');
      return state?['uiRequested'] == true;
    } on MissingPluginException catch (error, stackTrace) {
      _reportFlutterError(
        context: 'notifying Android that the Dart bootstrap is ready',
        error: error,
        stackTrace: stackTrace,
      );
      return false;
    } on PlatformException catch (error, stackTrace) {
      _reportFlutterError(
        context: 'notifying Android that the Dart bootstrap is ready',
        error: error,
        stackTrace: stackTrace,
      );
      return false;
    }
  }

  static Future<void> _initializeBackground(
    HeadlessLifecycleInitializer initializer,
  ) async {
    try {
      await Future<void>.sync(initializer);
      await _reportNativePhase('backgroundInitializationCompleted');
    } catch (error, stackTrace) {
      _reportFlutterError(
        context: 'running the host background initializer',
        error: error,
        stackTrace: stackTrace,
      );
      await _reportNativePhase('backgroundInitializationFailed', error: error);
      // Do not terminate the long-lived isolate. If the user opens the app,
      // allow the UI initializer to run so the host can recover or report the
      // failure through its normal startup path.
    }
  }

  static Future<void> _startUiIfNeeded() async {
    if (!_uiRequested || !_backgroundInitializationCompleted || _uiStarted) {
      return;
    }

    final HeadlessLifecycleInitializer? initializer = _uiInitializer;
    if (initializer == null) {
      return;
    }

    _uiStarted = true;
    _uiInitializationFuture ??= _initializeUi(initializer);
    await _uiInitializationFuture;
  }

  static Future<void> _initializeUi(
    HeadlessLifecycleInitializer initializer,
  ) async {
    try {
      await Future<void>.sync(initializer);
      await _reportNativePhase('uiInitializationCompleted');
    } catch (error, stackTrace) {
      _reportFlutterError(
        context: 'running the host UI initializer',
        error: error,
        stackTrace: stackTrace,
      );
      await _reportNativePhase('uiInitializationFailed', error: error);
      rethrow;
    }
  }

  static Future<void> _reportNativePhase(String phase, {Object? error}) async {
    try {
      await _channel.invokeMethod<void>('reportDartPhase', <String, Object?>{
        'phase': phase,
        if (error != null) 'error': error.toString(),
      });
    } on MissingPluginException {
      // Reporting is diagnostic only and must not break application startup.
    } on PlatformException {
      // Reporting is diagnostic only and must not break application startup.
    }
  }

  static void _reportFlutterError({
    required String context,
    required Object error,
    required StackTrace stackTrace,
  }) {
    FlutterError.reportError(
      FlutterErrorDetails(
        exception: error,
        stack: stackTrace,
        library: 'flutter_headless_lifecycle_bootstrap',
        context: ErrorDescription(context),
      ),
    );
  }

  /// Whether Android had already requested a visible Activity when this Dart
  /// bootstrap started.
  ///
  /// This value is finalized before [backgroundInitializer] runs. A `true`
  /// value means normal UI startup; `false` means a Service created the engine
  /// without an Activity.
  static bool get initialUiRequested => _initialUiRequested;

  /// Whether this isolate was initially created without a visible Activity.
  ///
  /// This is the value host applications should pass to plugins that need an
  /// explicit headless lifecycle pulse, such as Adjust session startup.
  static bool get startedHeadlessly => _hasStarted && !_initialUiRequested;

  /// Whether Android has requested UI at any point in this process.
  static bool get isUiRequested => _uiRequested;

  /// Current Dart-side state for diagnostics and tests.
  @visibleForTesting
  static Map<String, bool> get debugState => <String, bool>{
        'hasStarted': _hasStarted,
        'backgroundInitializationCompleted': _backgroundInitializationCompleted,
        'initialUiRequested': _initialUiRequested,
        'uiRequested': _uiRequested,
        'uiStarted': _uiStarted,
      };
}
