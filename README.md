# flutter_headless_lifecycle_bootstrap

Android-only Flutter 插件：当外部应用通过 `MediaRouteProviderService` 发现并创建本插件的 Service 时，在不启动 Activity 的情况下创建一个 FlutterEngine；之后用户真正打开 App 时，MainActivity 继续复用同一个 FlutterEngine。

这个插件解决以下问题：

- Android Service 拉起进程后，普通 Flutter `main()` 不会自动执行。
- 后台初始化需要一直等待 Adjust、cloak、referrer 等异步结果。
- 后台 FlutterEngine 和前台 FlutterEngine 同时存在时，部分插件的静态 MethodChannel 会互相覆盖。
- 多个 App 需要复用同一套 Service、FlutterEngine 和 Activity 接入逻辑。

## 工作流程

```text
YouTube/其他媒体应用发现 MediaRouteProviderService
    ↓
HeadlessLifecycleService.onCreate()
    ↓
PersistentFlutterEngineManager 创建唯一 FlutterEngine
    ↓
执行宿主 App 在 main.dart 中提供的 Dart entrypoint
    ↓
执行 backgroundInitializer
    ↓
FlutterEngine 和 Dart isolate 一直存在，不设置超时
    ↓
用户以后打开 App
    ↓
LifecycleFlutterActivity 复用同一个 FlutterEngine
    ↓
插件通知 Dart 执行 uiInitializer
    ↓
宿主 App 调用 runApp()
```

FlutterEngine 不会因为 `HeadlessLifecycleService.onDestroy()` 或 MainActivity 销毁而主动销毁。Android 系统杀掉整个进程后，FlutterEngine 会自然消失；插件不会尝试绕过系统进程管理。

## 一、在宿主 App 中添加依赖

在宿主 App 的 `pubspec.yaml` 中添加：

```yaml
dependencies:
  flutter_headless_lifecycle_bootstrap: ^0.0.1
```

然后执行：

```shell
flutter pub get
```

## 二、删除宿主 App 原来的 Lifecycle Service

插件已经通过自己的 AndroidManifest 声明：

```xml
<action android:name="android.media.MediaRouteProviderService" />
```

所以宿主 App 必须删除旧 Service，避免出现两个可被 YouTube 发现的 Service。

以 Apex09 为例，删除：

```text
android/app/src/main/kotlin/com/example/apex09/Apex09LifecycleService.kt
```

同时从 `android/app/src/main/AndroidManifest.xml` 删除：

```xml
<service
    android:name=".Apex09LifecycleService"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="${applicationId}.action.APEX09_LIFECYCLE_SERVICE" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
        <action android:name="android.media.MediaRouteProviderService" />
        <action android:name="android.intent.action.USER_PRESENT" />
    </intent-filter>
</service>
```

不要在宿主 App 中再次复制 `HeadlessLifecycleService`。

## 三、修改 MainActivity

### 使用 FlutterActivity 的项目

Apex09 当前使用 `FlutterActivity`，把：

```kotlin
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity()
```

改成：

```kotlin
package com.example.apex09

import com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap.LifecycleFlutterActivity

class MainActivity : LifecycleFlutterActivity()
```

### 使用 FlutterFragmentActivity 的项目

如果某个 App 原来继承 `FlutterFragmentActivity`，改为：

```kotlin
import com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap.LifecycleFlutterFragmentActivity

class MainActivity : LifecycleFlutterFragmentActivity()
```

不要在 MainActivity 中另外创建 FlutterEngine，也不要设置其他 cached engine。插件的 Activity 基类会：

- 创建或获取进程内唯一 FlutterEngine。
- 通知 Dart 需要显示 UI。
- Activity 销毁时保留 FlutterEngine。

如果 MainActivity 原来有自定义 `configureFlutterEngine()`、`onNewIntent()` 或其他代码，需要保留原逻辑并正常调用 `super`。

## 四、检查 Launcher intent-filter

MainActivity 保留以下内容：

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.LAUNCHER" />
</intent-filter>
```

插件不会自动修改宿主 App 的 MainActivity intent-filter，因为插件不知道每个 App 的 Activity 类名。

## 五、配置 Dart entrypoint

插件默认执行宿主 App `lib/main.dart` 中名为：

```dart
headlessLifecycleMain
```

的方法。如果使用默认名称，Manifest 不需要额外配置。

如果要遵循 App 自己的命名规范，例如 Apex09 使用：

```dart
apex09HeadlessLifecycleMain
```

需要在宿主 App 的 `<application>` 中添加：

```xml
<meta-data
    android:name="flutter_headless_lifecycle_bootstrap.dart_entrypoint"
    android:value="apex09HeadlessLifecycleMain" />
```

entrypoint 必须满足：

1. 写在宿主 App 的 `lib/main.dart` 中。
2. 必须是顶层方法，不能是类的静态方法或实例方法。
3. 必须添加 `@pragma('vm:entry-point')`，避免 release tree shaking 删除它。
4. 不能接收参数。

虽然插件也支持用以下 metadata 指定 Dart library URI：

```xml
<meta-data
    android:name="flutter_headless_lifecycle_bootstrap.dart_library"
    android:value="package:your_app/bootstrap.dart" />
```

但首轮接入不建议使用。优先把 entrypoint 放在 `main.dart`，减少 release 模式下的入口定位问题。

## 六、拆分原来的 main()

这是无法完全放进公共插件的部分，因为每个 App 的 Storage、Adjust、通知、广告、数据库和根 Widget 都不同。

原来的 `main()` 需要拆成：

```text
后台公共初始化 apex09InitializeBackground
UI初始化       apex09InitializeUi
Dart入口       apex09HeadlessLifecycleMain
```

### 6.1 Apex09 的 Dart 入口

在 `lib/main.dart` 中导入插件：

```dart
import 'package:flutter_headless_lifecycle_bootstrap/flutter_headless_lifecycle_bootstrap.dart';
```

增加入口：

```dart
@pragma('vm:entry-point')
Future<void> apex09HeadlessLifecycleMain() async {
  await FlutterHeadlessLifecycleBootstrap.run(
    backgroundInitializer: apex09InitializeBackground,
    uiInitializer: apex09InitializeUi,
  );
}
```

确保 Manifest 中配置的名称和这里完全一致，包括大小写。

### 6.2 后台公共初始化

建议 Apex09 使用：

```dart
Future<void> apex09InitializeBackground() async {
  WidgetsFlutterBinding.ensureInitialized();

  await Apex09StorageUtils.apex09InitStorage();

  Apex09NewNotificationUtils.instance.startSessionBackground();

  await Apex09NewNotificationUtils.instance
      .apex09EnsurePermissionAndInit(
        requestPermission: false,
      );

  await Apex09AdjustUtils.instance.apex09InitAdjust();
}
```

执行顺序不能随意交换：

1. 先初始化 Storage。
2. 再执行完整通知初始化，但必须传 `requestPermission: false`。
3. 最后启动 Adjust、cloak 和 referrer 判断。

A 包时 `apex09EnsurePermissionAndInit()` 仍然会注册普通通知。执行到 `startMediaNotificationsIfNeeded()` 时，因为 `apex09IsBPackage == false`，媒体通知会暂时跳过。

如果后续判断为 B 包，`Apex09AdjustUtils.refreshBUserState()` 会重新调用 `startMediaNotificationsIfNeeded()`，完成媒体通知注册。插件不会设置等待超时，所以只要进程和 FlutterEngine 还存在，后续回调仍可执行。

后台初始化中禁止执行以下操作：

- `Permission.notification.request()`。
- 打开 Activity 或系统设置页面。
- 显示广告。
- 页面跳转。
- `runApp()`。
- 依赖当前 Activity 的插件方法。
- 依赖已经构建完成的 GetX/Widget context。

### 6.3 UI 初始化

把 Apex09 原来 `main()` 中其余逻辑放入：

```dart
Future<void> apex09InitializeUi() async {
  await SystemChrome.setPreferredOrientations(
    <DeviceOrientation>[
      DeviceOrientation.portraitUp,
    ],
  );

  await Apex09AdSwitchUtils.instance.apex09Init();

  Apex09QuickActionsUtils.instance.apex09InitQuickActions(
    apex09UninstallTitle: Apex09LanguageUtils.instance
        .apex09QuickActionUninstallTitle(),
  );

  Apex09LaunchSourceUtils.instance.configureWidgetActionSource(
    consumeInitialWidgetAction:
        Apex09page.instance.apex09ConsumeInitialWidgetAction,
    widgetActionStreamProvider:
        Apex09page.instance.apex09WidgetActionStream,
  );

  await Apex09LaunchSourceUtils.instance.initLaunchSource();
  Apex09SqlUtils.instance.apex09InitSql();
  Apex09AdUtils.instance.apex09InitAd();

  Apex09NewNotificationUtils.instance
      .apex09SetShortcutPayloadNavigationHandler(
        apex09OpenMergePdf:
            Apex09PdfToolActionUtils.instance.apex09OpenMergePdf,
        apex09OpenScanToPdf:
            Apex09PdfToolActionUtils.instance.apex09OpenScanToPdf,
        apex09OpenWordToPdf:
            Apex09PdfToolActionUtils.instance.apex09OpenWordToPdf,
      );

  unawaited(Apex09ProcessingOverlayUtils.instance.apex09Init());
  Apex09TbaUtils.instance.apex09FlushPendingEvents();
  unawaited(Apex09TbaUtils.instance.apex09TrackInstallEvent());
  unawaited(Apex09TbaUtils.instance.apex09TrackSessionEvent());
  Apex09NewNotificationUtils.instance.consumeLaunchNotificationTracking();

  runApp(const Apex09App());
}
```

注意：

- 不要再次调用 `Apex09StorageUtils.apex09InitStorage()`。
- 不要再次调用 `Apex09NewNotificationUtils.instance.startSessionBackground()`。
- 不要再次调用 `Apex09AdjustUtils.instance.apex09InitAdjust()`。
- `runApp()` 只能调用一次。
- `Apex09LauncherController` 中前台调用 `apex09EnsurePermissionAndInit(requestPermission: true)` 可以保留，用于请求权限和完成前台逻辑。

### 6.4 原来的 main() 怎么处理

Android 不再依靠默认 `main()` 启动，而是由原生 Manager 执行配置的 entrypoint。为了让测试或其他平台仍有明确入口，可以保留：

```dart
Future<void> main() async {
  await apex09HeadlessLifecycleMain();
}
```

如果这个工程还支持 iOS、macOS、Windows 或 Web，不要直接照抄上面的 `main()`。本插件目前只声明 Android 平台，其他平台需要在 `main()` 中走原来的正常初始化和 `runApp()` 路径。

## 七、修改 Apex09AdjustUtils

### 7.1 增加一次性初始化保护

建议增加：

```dart
bool _apex09HasInitializedAdjust = false;

Future<void> apex09InitAdjust() async {
  if (_apex09HasInitializedAdjust) {
    return;
  }
  _apex09HasInitializedAdjust = true;

  // 原初始化逻辑
}
```

如果初始化过程可能同步抛错，并且希望以后重试，应在异常时把 `_apex09HasInitializedAdjust` 恢复为 `false`。

### 7.2 等待 referrer 配置加载

把：

```dart
apex09SetReferrerConfig();
refreshBUserState();
```

改成：

```dart
await apex09SetReferrerConfig();
refreshBUserState();
```

否则后台启动速度较快时，referrer 结果可能先回来，但用于判断的 referrer 配置列表仍未加载完成。

### 7.3 保留 B 包补注册逻辑

确认 `refreshBUserState()` 在状态从 A 切换为 B 时仍调用：

```dart
Apex09NewNotificationUtils.instance
    .startMediaNotificationsIfNeeded();
```

建议给 `startMediaNotificationsIfNeeded()` 增加“正在启动”的 Future 或布尔值，避免 Adjust、cloak、referrer 回调接近时并发注册媒体通知。

## 八、通知初始化注意事项

后台入口调用：

```dart
apex09EnsurePermissionAndInit(requestPermission: false)
```

因此必须确认这个方法满足：

- `requestPermission: false` 时不会调用通知权限弹窗。
- 不会主动启动页面。
- 通知点击监听可以在没有 Activity 时注册。
- 所调用的原生插件方法使用 `applicationContext`，不强依赖 Activity。
- A 包普通通知正常初始化。
- A 包的 `startMediaNotificationsIfNeeded()` 直接返回且不设置 `_hasStartedMediaNotifications = true`。
- B 包状态回来后允许再次调用媒体通知注册。

如果通知标题或正文依赖 GetX `.tr`，要检查在 `runApp(GetMaterialApp(...))` 之前是否能返回正确文本。后台首次拉起时 Widget 和 GetX 页面尚未创建。必要时改用不依赖 Widget context 的本地语言工具，或者先初始化翻译数据。

## 九、验证 Manifest 合并结果

执行一次构建：

```shell
flutter build apk --debug
```

然后在项目构建目录中搜索：

```shell
rg -n "HeadlessLifecycleService|MediaRouteProviderService" build/app/intermediates
```

最终合并 Manifest 中应该只有插件的：

```text
com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap.HeadlessLifecycleService
```

不应该继续出现：

```text
com.example.apex09.Apex09LifecycleService
```

同一个应用内也不应该有第二个声明 `android.media.MediaRouteProviderService` 的 Service，除非它确实实现了真正的 MediaRoute Provider 协议并且是业务需要的。

## 十、调试日志

Android 端统一使用日志 Tag：

```text
HeadlessLifecycle
```

查看日志：

```shell
adb logcat -s HeadlessLifecycle
```

关键日志顺序应该是：

```text
serviceOnCreate
createEngine source=lifecycle_service_on_create
pluginAttached
dartBootstrapReady
dartPhase phase=backgroundInitializationCompleted
```

用户随后打开 App 时应看到：

```text
reuseEngine source=flutter_activity requestUi=true
requestUi
dartPhase phase=uiInitializationCompleted
```

不应该再次出现第二条 `createEngine`。

## 十一、必须执行的测试清单

### 场景 1：安装后从未手动打开 App

1. 安装 release/debug 包。
2. 不点击 Apex09 图标。
3. 打开 YouTube。
4. 查看是否出现 `serviceOnCreate`。
5. 查看是否出现 `backgroundInitializationCompleted`。
6. 验证普通通知配置已经注册。

### 场景 2：默认 A 包

1. 确认 Adjust 结果尚未返回或判断为 A。
2. 验证普通通知正常。
3. 验证媒体通知尚未注册。

### 场景 3：延迟变成 B 包

1. 保持进程存在。
2. 等待 Adjust、cloak、referrer 回调。
3. 确认 `refreshBUserState()` 变成 B。
4. 确认 `startMediaNotificationsIfNeeded()` 被再次调用。
5. 确认媒体通知注册成功。

### 场景 4：后台 Engine 已存在后打开 App

1. 先通过 YouTube 创建后台 Engine。
2. 点击桌面图标。
3. 确认只出现 `reuseEngine`，没有第二次 `createEngine`。
4. 确认启动页和首页正常显示。
5. 确认通知点击、悬浮窗点击、广告和页面跳转正常。

### 场景 5：先正常打开 App

1. 清理应用进程。
2. 直接点击桌面图标。
3. 确认 Activity 创建唯一 Engine。
4. 确认后台初始化完成后执行 UI 初始化。
5. 再打开 YouTube。
6. 确认 Service 复用已有 Engine。

### 场景 6：进入后台和重新进入前台

1. 打开 App。
2. 按 Home。
3. 再进入 App。
4. 确认 Engine 没有重建。
5. 确认 AppLifecycle、热启动广告和通知点击逻辑没有重复触发。

### 场景 7：没有通知权限

1. Android 13 及以上拒绝通知权限。
2. 通过 YouTube 拉起。
3. 确认后台没有弹权限框。
4. 验证当前项目预期的媒体通知路径。
5. 用户真正打开 App 后，再由 LauncherController 请求权限。

### 场景 8：Release/R8

1. 构建 release APK/AAB。
2. 安装 release 包。
3. 重复“从未打开 App，先打开 YouTube”的测试。
4. 如果日志只有 `createEngine`，没有 `dartBootstrapReady`，优先检查：
   - entrypoint 名称是否完全一致。
   - 是否添加 `@pragma('vm:entry-point')`。
   - entrypoint 是否为顶层方法。
   - entrypoint 是否位于配置的 Dart library 中。

## 十二、重要限制和注意事项

### 1. 强行停止后无法保证拉起

如果用户在系统设置中点击“强行停止”，Android 通常会阻止该应用的 Service、Receiver、Alarm 和 Job 再次运行，直到用户主动打开 App。插件不能绕过系统限制。

### 2. FlutterEngine 常驻不等于进程永远存活

插件只是不主动调用 `FlutterEngine.destroy()`。FlutterEngine 本身不会提高 Android 进程优先级。系统内存不足时仍然可以杀掉进程。

`flutter_local_notification_plugins` 自己的 ForegroundService、Job、WorkManager 和 Alarm 能提高部分任务的恢复能力，但不代表进程绝对不会被杀。

### 3. MediaRouteProviderService 是系统协议 action

插件按照当前业务示例声明了：

```xml
<action android:name="android.media.MediaRouteProviderService" />
```

但 `HeadlessLifecycleService.onBind()` 返回 `null`，它不是真正实现完整 MediaRoute Provider 协议的服务。外部媒体 App 是否发现、创建以及维持该 Service，可能随 Android 版本、厂商系统和媒体 App 版本变化。

### 4. Service 是 exported

为了让外部媒体应用发现它，Service 使用：

```xml
android:exported="true"
```

这意味着其他应用也可能显式尝试绑定或启动它。Service 不接受外部参数、不返回 Binder，也不执行文件或账号操作；不要在 Service 中增加可由外部 Intent 控制的敏感功能。

### 5. 当前仅支持 Android

插件 `pubspec.yaml` 只声明 Android 平台。iOS 和其他平台需要继续使用宿主 App 原来的启动流程。

插件 Android `minSdk` 是 24。宿主 App 的 `minSdk` 不能低于 24；Apex09 当前是 26，可以直接接入。

### 6. 同一进程只能由本插件管理一个 Engine

不要同时使用：

- 其他 `FlutterEngineCache`。
- App 自己创建的后台 FlutterEngine。
- 第二个 FlutterActivity 默认 Engine。
- 另一个 headless 插件创建的 Engine。

特别是 `flutter_local_notification_plugins 0.2.8` 使用进程级静态通知事件 Channel。两个 Engine 同时注册该插件时，通知点击和悬浮窗点击事件可能被覆盖或清空。

### 7. 所有插件会在无 Activity 时注册

`FlutterEngine(applicationContext)` 会自动注册宿主 App pubspec 中的 Android 插件。ActivityAware 插件此时只有 Engine，没有 Activity。

后台初始化只能调用确认支持 applicationContext 的方法。需要 Activity 的权限、广告、文件选择器、相机、分享、打印等方法必须放到 `uiInitializer` 或页面逻辑中。

### 8. 后台初始化异常

插件会通过 `FlutterError.reportError` 和 Android 日志记录后台异常，并继续保留 isolate。如果此时用户打开 App，插件仍会执行 `uiInitializer`，让宿主 App 有机会进入正常页面或展示自己的错误处理。

因此业务初始化方法本身应当具备幂等性，并对关键失败做好日志和降级。

## 十三、多个 App 接入时的推荐规范

每个 App 只保留以下差异：

```text
App自己的 entrypoint 名称
App自己的 Storage 初始化
App自己的通知工具类
App自己的 Adjust 工具类
App自己的 UI 初始化
App自己的根 Widget
```

公共部分全部由本插件维护：

```text
Android Service
Manifest intent-filter
唯一 FlutterEngine
Engine 常驻
Activity 复用 Engine
Android/Dart 握手
UI 请求防丢失
后台/UI 初始化只执行一次
R8 keep rules
统一调试日志
```

以后插件逻辑有调整时，只升级公共插件，不要把 Service 和 Engine Manager 复制到每个 App 中。
