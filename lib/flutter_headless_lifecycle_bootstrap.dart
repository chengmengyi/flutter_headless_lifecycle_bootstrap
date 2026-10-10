
import 'flutter_headless_lifecycle_bootstrap_platform_interface.dart';

class FlutterHeadlessLifecycleBootstrap {
  Future<String?> getPlatformVersion() {
    return FlutterHeadlessLifecycleBootstrapPlatform.instance.getPlatformVersion();
  }
}
