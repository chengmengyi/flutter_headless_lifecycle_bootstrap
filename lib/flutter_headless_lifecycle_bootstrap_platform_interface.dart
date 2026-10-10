import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'flutter_headless_lifecycle_bootstrap_method_channel.dart';

abstract class FlutterHeadlessLifecycleBootstrapPlatform extends PlatformInterface {
  /// Constructs a FlutterHeadlessLifecycleBootstrapPlatform.
  FlutterHeadlessLifecycleBootstrapPlatform() : super(token: _token);

  static final Object _token = Object();

  static FlutterHeadlessLifecycleBootstrapPlatform _instance = MethodChannelFlutterHeadlessLifecycleBootstrap();

  /// The default instance of [FlutterHeadlessLifecycleBootstrapPlatform] to use.
  ///
  /// Defaults to [MethodChannelFlutterHeadlessLifecycleBootstrap].
  static FlutterHeadlessLifecycleBootstrapPlatform get instance => _instance;

  /// Platform-specific implementations should set this with their own
  /// platform-specific class that extends [FlutterHeadlessLifecycleBootstrapPlatform] when
  /// they register themselves.
  static set instance(FlutterHeadlessLifecycleBootstrapPlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<String?> getPlatformVersion() {
    throw UnimplementedError('platformVersion() has not been implemented.');
  }
}
