import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'flutter_headless_lifecycle_bootstrap_platform_interface.dart';

/// An implementation of [FlutterHeadlessLifecycleBootstrapPlatform] that uses method channels.
class MethodChannelFlutterHeadlessLifecycleBootstrap extends FlutterHeadlessLifecycleBootstrapPlatform {
  /// The method channel used to interact with the native platform.
  @visibleForTesting
  final methodChannel = const MethodChannel('flutter_headless_lifecycle_bootstrap');

  @override
  Future<String?> getPlatformVersion() async {
    final version = await methodChannel.invokeMethod<String>('getPlatformVersion');
    return version;
  }
}
