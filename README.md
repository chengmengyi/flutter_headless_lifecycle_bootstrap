# flutter_headless_lifecycle_bootstrap

Android-only Flutter 插件：当外部应用通过 `MediaRouteProviderService` 发现并创建本插件的 Service 时，在不启动 Activity 的情况下创建一个 FlutterEngine；之后用户真正打开 App 时，MainActivity 继续复用同一个 FlutterEngine。

这个插件解决以下问题：

- Android Service 拉起进程后，普通 Flutter `main()` 不会自动执行。
- 后台初始化需要一直等待 Adjust/AppsFlyer（AF）、cloak、referrer 等异步结果。
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

默认情况下，FlutterEngine 不会因为 `HeadlessLifecycleService.onDestroy()` 或 MainActivity 销毁而主动销毁。宿主也可以开启“UI Activity 销毁时销毁 Engine”：划掉任务或退出 Activity 后清空 Dart isolate 和路由，下次由 YouTube/其他媒体应用或 App 图标重新创建唯一 Engine。Android 系统杀掉整个进程后，FlutterEngine 会自然消失；插件不会尝试绕过系统进程管理。

## 一、在宿主 App 中添加依赖

在宿主 App 的 `pubspec.yaml` 中添加：

```yaml
dependencies:
  flutter_headless_lifecycle_bootstrap: 最新版本
  # 二选一：
  flutter_check_adjust: 最新版本
  # flutter_check_af_new: 最新版本
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

例如宿主原来存在以下 Service 文件，需要删除：

```text
android/app/src/main/kotlin/com/example/app/LegacyLifecycleService.kt
```

同时从 `android/app/src/main/AndroidManifest.xml` 删除：

```xml
<service
    android:name=".LegacyLifecycleService"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="${applicationId}.action.LIFECYCLE_SERVICE" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
        <action android:name="android.media.MediaRouteProviderService" />
        <action android:name="android.intent.action.USER_PRESENT" />
    </intent-filter>
</service>
```

不要在宿主 App 中再次复制 `HeadlessLifecycleService`。

## 三、修改 MainActivity

### 使用 FlutterActivity 的项目

如果宿主当前使用 `FlutterActivity`，把：

```kotlin
import io.flutter.embedding.android.FlutterActivity

class MainActivity : FlutterActivity()
```

改成：

```kotlin
package com.example.app

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
- 默认在 Activity 销毁时保留 FlutterEngine。
- 宿主开启可选配置后，在非配置变更导致的 Activity 销毁时安全销毁 Engine。

如果 MainActivity 原来有自定义 `configureFlutterEngine()`、`onNewIntent()` 或其他代码，需要保留原逻辑并正常调用 `super`。

### Activity 销毁后按冷启动重新进入

如果产品要求“从最近任务划掉 App 后，再点图标必须重新执行 Dart entrypoint、显示启动页并按启动逻辑选择下一页”，在宿主 App 的 `<application>` 中添加：

```xml
<meta-data
    android:name="flutter_headless_lifecycle_bootstrap.destroy_engine_when_ui_destroyed"
    android:value="true" />
```

开启后：

- `LifecycleFlutterActivity`/`LifecycleFlutterFragmentActivity` 因非配置变更而销毁时，由插件的 Engine Manager 销毁共享 Engine。
- 插件会同步清空 Engine、MethodChannel、Dart ready、UI requested 和创建来源状态，不会留下已销毁的 Engine 引用。
- 下次点击 App 图标时创建新 Engine，重新执行 `main()` 和宿主的后台/UI 初始化。
- 下次外部媒体应用重新发现、绑定或启动 `HeadlessLifecycleService` 时，也会按需创建新 Engine。
- 屏幕旋转等 `isChangingConfigurations == true` 的情况不会触发销毁。

不要为了实现这个行为把 MainActivity 改回普通 `FlutterActivity`，否则 Service Engine 和 Activity Engine 可能同时存在。也不要自行把 `shouldDestroyEngineWithHost()` 改为 `true`；共享 Engine 由 Manager 持有，必须由 Manager 在销毁时同步清理引用。

如果不添加上述 metadata，默认行为保持不变：Activity 销毁后 Engine 和 Dart isolate 继续存在。

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

如果要遵循 App 自己的命名规范，例如改成：

```dart
customHeadlessLifecycleMain
```

需要在宿主 App 的 `<application>` 中添加：

```xml
<meta-data
    android:name="flutter_headless_lifecycle_bootstrap.dart_entrypoint"
    android:value="customHeadlessLifecycleMain" />
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

这是无法完全放进公共插件的部分，因为每个 App 的 Storage、Adjust/AF、通知、广告、数据库和根 Widget 都不同。

原来的 `main()` 需要拆成：

```text
后台公共初始化 initializeBackground
UI初始化       initializeUi
Dart入口       headlessLifecycleMain
```

### 6.1 宿主 App 的 Dart 入口

在 `lib/main.dart` 中导入插件：

```dart
import 'package:flutter_headless_lifecycle_bootstrap/flutter_headless_lifecycle_bootstrap.dart';
```

增加入口：

```dart
@pragma('vm:entry-point')
Future<void> headlessLifecycleMain() async {
  await FlutterHeadlessLifecycleBootstrap.run(
    backgroundInitializer: initializeBackground,
    uiInitializer: initializeUi,
  );
}
```

这里使用插件默认入口名，所以不需要额外配置 entrypoint metadata。如果自行改名，
Manifest 中配置的名称必须和代码完全一致，包括大小写。

### 6.2 后台公共初始化

宿主 App 可以按下面的结构初始化：

```dart
Future<void> initializeBackground() async {
  WidgetsFlutterBinding.ensureInitialized();

  await AppStorageUtils.initStorage();

  // Service 无界面拉活也上报 install；不要在这里上报 session。
  unawaited(AppTbaUtils.instance.trackInstallEvent());

  // 后台首次拉起时 GetMaterialApp 尚未创建，必须先手动初始化 GetX 翻译。
  Get.addTranslations(AppTranslations().keys);
  Get.locale = AppLanguageUtils.instance.currentLocale();
  Get.fallbackLocale = const Locale('en', 'US');

  AppNotificationUtils.instance.startSessionBackground();

  await AppNotificationUtils.instance
      .ensurePermissionAndInit(
        requestPermission: false,
      );

  // Adjust 项目：需要把真实的初始启动来源传给 Adjust 工具类。
  await AppAdjustUtils.instance.initAdjust(
    headlessStart: FlutterHeadlessLifecycleBootstrap.startedHeadlessly,
  );

  // AF 项目改为调用自己的 AF 初始化；不需要 headlessStart 参数。
  // await AppAfUtils.instance.initAf();
}
```

示例中的 `AppStorageUtils`、`AppNotificationUtils`、`AppAdjustUtils`、`AppAfUtils` 等都是
通用占位名称，需要替换成宿主项目自己的类和方法；公共插件不要求这些类真实存在。

执行顺序不能随意交换：

1. 先初始化 Storage。
2. 初始化通知文案依赖的本地语言和 GetX 翻译数据。
3. 再执行完整通知初始化，但必须传 `requestPermission: false`。
4. 最后启动归因、cloak 和 referrer 判断。Adjust 项目还要把本次是否由无界面
   Service 拉起传给 Adjust 工具类；AF 项目不需要该参数。

#### 6.2.1 Headless 上报业务 install，但不上报业务 session

如果希望 Service 无界面拉活也计为安装上报，把业务 install 放进
`initializeBackground()`；不要把宿主自己的业务 session 放进去。这里不包含
Adjust/AF 为获取归因而发送的 SDK Session：

```dart
unawaited(AppTbaUtils.instance.trackInstallEvent());
```

UI 初始化中仍然保留 install 和 session：

```dart
// Headless install 已成功时直接跳过；失败时在真正打开 App 后重试。
unawaited(AppTbaUtils.instance.trackInstallEvent());

// 只在真实 UI 启动时上报 session。
unawaited(AppTbaUtils.instance.trackSessionEvent());
```

install 方法除了“成功后持久化已上报状态”，还必须增加“请求进行中”的 Future
保护。否则 Headless 请求尚未结束时用户打开 App，后台和 UI 可能并发上报两次：

```dart
Future<bool>? _installFuture;

Future<bool> trackInstallEvent({
  bool force = false,
  bool limitedTracking = false,
}) async {
  if (!force && installReportedStorage.getData()) {
    return true;
  }
  if (!force) {
    final Future<bool>? runningFuture = _installFuture;
    if (runningFuture != null) {
      return runningFuture;
    }
  }

  final Future<bool> future = _trackInstallEventInternal(
    limitedTracking: limitedTracking,
  );
  if (force) {
    return future;
  }

  _installFuture = future;
  try {
    return await future;
  } finally {
    if (identical(_installFuture, future)) {
      _installFuture = null;
    }
  }
}

Future<bool> _trackInstallEventInternal({
  required bool limitedTracking,
}) async {
  final Map<String, dynamic> payload = await buildInstallPayload(
    limitedTracking: limitedTracking,
  );
  final bool success = await sendSinglePayload(
    type: 'install',
    payload: payload,
  );
  if (success) {
    await installReportedStorage.saveData(true);
  }
  return success;
}
```

`force: true` 表示调用方明确要求强制上报，因此会绕过持久化状态和进行中 Future
保护。普通调用共享同一个 Future：成功后永久跳过，失败后清空 Future，允许 UI
启动或下次进程启动时再次尝试。

A 包时 `ensurePermissionAndInit()` 仍然会注册普通通知。执行到 `startMediaNotificationsIfNeeded()` 时，因为 `isBPackage == false`，媒体通知会暂时跳过。

如果后续判断为 B 包，宿主的归因工具类会在 `refreshBUserState()` 中重新调用
`startMediaNotificationsIfNeeded()`，完成媒体通知注册。插件不会设置等待超时，
所以只要进程和 FlutterEngine 还存在，后续回调仍可执行。

后台初始化中禁止执行以下操作：

- `Permission.notification.request()`。
- 打开 Activity 或系统设置页面。
- 显示广告。
- 页面跳转。
- `runApp()`。
- 依赖当前 Activity 的插件方法。
- 依赖已经构建完成的 GetX/Widget context。

### 6.3 UI 初始化

把宿主 App 原来 `main()` 中其余逻辑放入：

```dart
Future<void> initializeUi() async {
  // 仅 Adjust 项目需要，而且必须放在 UI 初始化最前面。
  // AF 项目删除这一行，不需要进行生命周期交接。
  FlutterCheckAdjust.instance.switchToUiLifecycle();

  await SystemChrome.setPreferredOrientations(
    <DeviceOrientation>[
      DeviceOrientation.portraitUp,
    ],
  );

  await AppAdSwitchUtils.instance.init();

  AppQuickActionsUtils.instance.initQuickActions(
    uninstallTitle: AppLanguageUtils.instance.quickActionUninstallTitle(),
  );

  AppLaunchSourceUtils.instance.configureWidgetActionSource(
    consumeInitialWidgetAction:
        AppPage.instance.consumeInitialWidgetAction,
    widgetActionStreamProvider:
        AppPage.instance.widgetActionStream,
  );

  await AppLaunchSourceUtils.instance.initLaunchSource();
  AppSqlUtils.instance.initSql();
  AppAdUtils.instance.initAd();

  AppNotificationUtils.instance
      .setShortcutPayloadNavigationHandler(
        openMergePdf: AppPdfToolActionUtils.instance.openMergePdf,
        openScanToPdf: AppPdfToolActionUtils.instance.openScanToPdf,
        openWordToPdf: AppPdfToolActionUtils.instance.openWordToPdf,
      );

  unawaited(AppProcessingOverlayUtils.instance.init());
  AppTbaUtils.instance.flushPendingEvents();
  unawaited(AppTbaUtils.instance.trackInstallEvent());
  unawaited(AppTbaUtils.instance.trackSessionEvent());
  AppNotificationUtils.instance.consumeLaunchNotificationTracking();

  runApp(const App());
}
```

注意：

- 不要再次调用 `AppStorageUtils.initStorage()`。
- 不要再次调用 `AppNotificationUtils.instance.startSessionBackground()`。
- 不要再次调用已经在后台执行过的 Adjust/AF 初始化方法。
- `runApp()` 只能调用一次。
- `LauncherController` 中前台调用 `ensurePermissionAndInit(requestPermission: true)` 可以保留，用于请求权限和完成前台逻辑。

### 6.4 原来的 main() 怎么处理

Android 不再依靠默认 `main()` 启动，而是由原生 Manager 执行配置的 entrypoint。为了让测试或其他平台仍有明确入口，可以保留：

```dart
Future<void> main() async {
  await headlessLifecycleMain();
}
```

如果这个工程还支持 iOS、macOS、Windows 或 Web，不要直接照抄上面的 `main()`。本插件目前只声明 Android 平台，其他平台需要在 `main()` 中走原来的正常初始化和 `runApp()` 路径。

## 七、接入 Adjust 或 AppsFlyer（AF）

归因方案二选一：

- Adjust 使用 7.1～7.5 的模拟前台与 UI 生命周期交接方案。
- AF 使用 7.1～7.3 的通用初始化要求，以及 7.6 的 Application Context 与手动
  Headless Session 方案。

不要把 Adjust 的 `onResume/onPause`、15 秒安全窗口或
`switchToUiLifecycle()` 搬到 AF 项目；也不要在 Adjust 项目中套用 AF 的
`logSession(applicationContext)`。

### 7.1 增加一次性初始化保护

归因工具类都建议增加一次性初始化保护。以下以 Adjust 为例：

```dart
bool _hasInitializedAdjust = false;

Future<void> initAdjust({bool headlessStart = false}) async {
  if (_hasInitializedAdjust) {
    return;
  }
  _hasInitializedAdjust = true;

  // 原初始化逻辑
}
```

如果初始化过程可能同步抛错，并且希望以后重试，应在异常时把 `_hasInitializedAdjust` 恢复为 `false`。

### 7.2 等待 referrer 配置加载

把：

```dart
setReferrerConfig();
refreshBUserState();
```

改成：

```dart
await setReferrerConfig();
refreshBUserState();
```

否则后台启动速度较快时，referrer 结果可能先回来，但用于判断的 referrer 配置列表仍未加载完成。

### 7.3 保留 B 包补注册逻辑

确认 `refreshBUserState()` 在状态从 A 切换为 B 时仍调用：

```dart
AppNotificationUtils.instance
    .startMediaNotificationsIfNeeded();
```

建议给 `startMediaNotificationsIfNeeded()` 增加“正在启动”的 Future 或布尔值，避免归因、cloak、referrer 回调接近时并发注册媒体通知。

### 7.4 把 Headless 启动状态传给 flutter_check_adjust

只升级 Adjust SDK 不能解决纯 Service 拉活时收不到首次归因的问题。Adjust 的
Android 生命周期仍然依赖 Activity 的 `onResume/onPause`；无 Activity 时首次
session 不会自动启动。

`flutter_check_adjust` 提供了受控的 Headless 生命周期补偿。把宿主 App 的
`AppAdjustUtils.initAdjust()` 改成可接收启动状态：

```dart
Future<void> initAdjust({bool headlessStart = false}) async {
  if (_hasInitializedAdjust) {
    return;
  }
  _hasInitializedAdjust = true;

  await setReferrerConfig();
  refreshBUserState();

  // 省略 distinctId、cloakData 和 RequestCallback 的原有组装代码。
  FlutterCheckAdjust.instance.init(
    adjustAppToken: AppLocalInfo.adjustToken,
    distinctId: distinctId,
    clockUrl: AppLocalInfo.cloakUrl,
    cloakWhiteKey: 'anatole',
    cloakData: cloakData,
    requestCallback: requestCallback,
    headlessStart: headlessStart,
  );
}
```

后台初始化时必须传入插件在握手阶段固定下来的启动状态：

```dart
await AppAdjustUtils.instance.initAdjust(
  headlessStart:
      FlutterHeadlessLifecycleBootstrap.startedHeadlessly,
);
```

不要使用稍后会变化的 `isUiRequested` 来判断初始启动来源。用户可能在后台
初始化过程中点击 App 图标，此时 `isUiRequested` 会从 `false` 变为 `true`，
而 `startedHeadlessly` 始终保留本次 Engine 的真实创建方式。

这个参数的行为是：

- 正常点击图标启动时为 `false`，不手动干预 Adjust，由 Activity 自动管理。
- YouTube/Service 无界面拉活时为 `true`，初始化后补一次
  `Adjust.onResume()`，使首次 session 和归因请求真正开始。
- attribution 回调到达后，只执行一次 `Adjust.onPause()`，结束模拟前台。
- session 成功或失败不结束模拟前台，因为 session 响应不代表 attribution
  回调已经派发。
- 默认 15 秒安全定时器只负责结束模拟前台，不销毁 FlutterEngine、不移除
  attribution 回调，也不结束宿主的后台等待逻辑。

如需调整安全窗口，可以在初始化时传入：

```dart
headlessLifecycleTimeout: const Duration(seconds: 15),
```

不要再在宿主 App 或 `request_adjust.dart` 中无条件调用
`Adjust.onResume()`。否则正常启动时会同时收到 Activity 自动生命周期和手动
生命周期。

### 7.5 UI 出现时交接 Adjust 生命周期

如果用户在 15 秒窗口内点击图标，需要结束模拟前台并让 Adjust 最终保持真实
Activity 前台状态。因此在 `initializeUi()` 的第一行调用：

```dart
FlutterCheckAdjust.instance.switchToUiLifecycle();
```

这个方法是幂等的：

- 本来就是正常 UI 启动时不做任何事。
- Headless 生命周期已经被回调或定时器结束时不做任何事。
- 只有 Headless 模拟前台仍在进行时，才按 `onPause → onResume` 完成交接。

后续用户按 Home、重新进入 App 等生命周期继续由 Adjust Android SDK 自动
管理，宿主不需要再次调用该方法。

### 7.6 AppsFlyer（AF）的 Headless Session

AF 不使用 Adjust 的模拟前台方案，也不需要 `headlessStart`、15 秒安全窗口或
UI 生命周期交接。宿主在 `initializeBackground()` 中正常初始化 AF：

```dart
await AppAfUtils.instance.initAf();
```

`flutter_check_af_new` 的 Android 实现需要满足以下行为：

1. `initSdk` 使用 `applicationContext`，不能因为没有 Activity 而丢弃 MethodChannel 调用。
2. `setCustomerUserId` 和转换数据监听必须在发送 Session 前完成。
3. 调用 `AppsFlyerLib.start(applicationContext)` 初始化 SDK。
4. 如果当前没有 Activity，只调用一次
   `AppsFlyerLib.logSession(applicationContext)`，主动发送 Headless Session。
5. 如果已有 Activity，不手动调用 `logSession`，由 AF 的真实 Activity 生命周期管理 Session。

AF 项目的 `initializeUi()` 不需要调用任何类似
`switchToUiLifecycle()` 的方法。后台 Engine 后来绑定 Activity 时，AF 插件通过
`ActivityAware.onAttachedToActivity()` 获取真实 Activity；后续 Home、重新进入等
前后台切换由 AF SDK 自动处理。

Headless Session 必须有进程内一次性保护，避免 Service 多次 `onCreate/onBind`
重复发送。建议保留以下关键日志用于验证：

```text
check user---> af channel initSdk result--->success
check user---> af native start entered--->{context: android.app.Application, hasActivity: false}
check user---> af native headless logSession called
check user---> request af result--->...
```

## 八、通知初始化注意事项

后台入口调用：

```dart
ensurePermissionAndInit(requestPermission: false)
```

因此必须确认这个方法满足：

- `requestPermission: false` 时不会调用通知权限弹窗。
- 不会主动启动页面。
- 通知点击监听可以在没有 Activity 时注册。
- 所调用的原生插件方法使用 `applicationContext`，不强依赖 Activity。
- A 包普通通知正常初始化。
- A 包的 `startMediaNotificationsIfNeeded()` 直接返回且不设置 `_hasStartedMediaNotifications = true`。
- B 包状态回来后允许再次调用媒体通知注册。

### 8.1 GetX `.tr` 必须在通知初始化前准备好

如果通知标题、正文或按钮依赖 GetX `.tr`，不能直接认为后台入口已经具备翻译环境。

后台首次拉起时 `runApp()` 还没有执行，`GetMaterialApp` 也没有创建，因此以下配置尚未自动写入 GetX：

```dart
translations: AppTranslations(),
locale: AppLanguageUtils.instance.currentLocale(),
fallbackLocale: const Locale('en', 'US'),
```

当 `Get.locale` 为空时，GetX 的 `.tr` 会直接返回原始 key。例如：

```dart
'Home'.tr;              // 返回 Home
'Check'.tr;             // 返回 Check
'File size reduced'.tr; // 返回 File size reduced
```

它不会报错，也不会返回空字符串，但中文、日文等设备或已保存的 App 语言仍会得到英文 key，不是正确的本地化文本。

宿主 App 的通知初始化中，以下内容会受到影响：

- 常驻快捷通知的 `Home`、`Merge`、`Import`、`Convert`。
- 自定义通知按钮的 `Check`。
- 新文件通知的标题和正文。
- 周期通知、广播通知和媒体通知的标题、正文及 Channel 描述。

部分周期通知和广播通知注册后会设置“已注册”标记。即使用户稍后真正打开 App，前台再次调用通知初始化也可能因为这些标记而跳过注册，所以不能依靠 `GetMaterialApp` 创建后自动修正文案。

#### 通用接入方式

在 `AppStorageUtils.initStorage()` 完成之后、任何通知初始化之前，手动写入翻译表、当前语言和回退语言：

确认 `main.dart` 可以使用 Flutter 的 `Locale` 和 GetX 的 `Get`；如果项目没有通过自己的公共库导出它们，可直接导入：

```dart
import 'package:flutter/material.dart';
import 'package:get/get.dart';
```

```dart
Future<void> initializeBackground() async {
  WidgetsFlutterBinding.ensureInitialized();

  await AppStorageUtils.initStorage();

  unawaited(AppTbaUtils.instance.trackInstallEvent());

  Get.addTranslations(AppTranslations().keys);
  Get.locale = AppLanguageUtils.instance.currentLocale();
  Get.fallbackLocale = const Locale('en', 'US');

  AppNotificationUtils.instance.startSessionBackground();

  await AppNotificationUtils.instance
      .ensurePermissionAndInit(
        requestPermission: false,
      );

  // Adjust 项目：
  await AppAdjustUtils.instance.initAdjust(
    headlessStart: FlutterHeadlessLifecycleBootstrap.startedHeadlessly,
  );

  // AF 项目二选一改为：
  // await AppAfUtils.instance.initAf();
}
```

注意调用顺序：

1. 必须先初始化 Storage，因为 `currentLocale()` 会读取用户已经保存的语言。
2. 然后调用 `Get.addTranslations()`、设置 `Get.locale` 和 `Get.fallbackLocale`。
3. 最后才能调用任何会计算 `.tr` 文案的通知方法。

这段初始化不依赖 Widget 或 `BuildContext`，可以在无 Activity 的后台 FlutterEngine 中执行。后面创建 `GetMaterialApp` 时再次添加相同翻译数据没有问题。

其他 App 接入本插件时，如果翻译类、语言存储或命名不同，需要替换为该 App 自己的实现。公共插件无法代替宿主 App 初始化业务翻译表。

如果不希望提前初始化 GetX，也可以实现一个直接读取本地翻译 Map 的语言工具，并让后台通知完全不使用 `.tr`；但同一 App 中只能选择一套明确、可测试的后台翻译方案。

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
com.example.app.LegacyLifecycleService
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

Adjust 项目通过 Service 首次拉活时，Flutter debug 日志还应出现：

```text
check user---> start request adjust
check user---> start headless adjust lifecycle
check user---> request adjust result--->network:...
check user---> finish headless adjust lifecycle--->...
```

最后一条的原因只可能是 attribution callback、safety timeout 或 UI 主动交接。
正常点击图标冷启动时不应该出现 `start headless adjust lifecycle`。

AF 项目通过 Service 首次拉活时应出现：

```text
check user---> af channel initSdk result--->success
check user---> af channel conversion listener ready
check user---> af native start entered--->{context: android.app.Application, hasActivity: false}
check user---> af native headless logSession called
check user---> request af result--->...
```

AF 项目不应该出现 Adjust 的 `start headless adjust lifecycle` 或
`switchToUiLifecycle` 日志。

## 十一、必须执行的测试清单

测试前根据归因方案选择日志命令。

Adjust：

```shell
adb logcat -v time \
  'HeadlessLifecycle:D' \
  'flutter:I' \
  'Adjust:V' \
  'AdjustBridge:V' \
  '*:S' | tee "$HOME/Downloads/adjust_headless.txt"
```

AF：

```shell
adb logcat -v time \
  'HeadlessLifecycle:D' \
  'AppsFlyer_6.15.2:V' \
  'AppsFlyer_FlutterPlugin:V' \
  'flutter:I' \
  '*:S' | tee "$HOME/Downloads/af_headless.txt"
```

这里使用 `$HOME`，不依赖 `/Users/某个用户名`，换电脑后仍会保存到当前登录
用户的 Downloads 目录。按 `Ctrl+C` 停止抓取；同名文件会被覆盖。如果需要保留
上一次日志，把 `tee` 改成 `tee -a` 以追加写入。

### 场景 1：安装后从未手动打开 App

1. 安装 release/debug 包。
2. 不点击宿主 App 图标。
3. 打开 YouTube。
4. 查看是否出现 `serviceOnCreate`。
5. 查看是否出现 `backgroundInitializationCompleted`。
6. 确认出现一次 install 请求及结果日志。
7. 确认没有宿主自己的业务 session 请求；Adjust/AF 为获取归因发送的 SDK Session
   应按各自方案正常出现。
8. 验证普通通知配置已经注册。

### 场景 2：默认 A 包

1. 确认 Adjust/AF 结果尚未返回或判断为 A。
2. 验证普通通知正常。
3. 验证媒体通知尚未注册。

### 场景 3：延迟变成 B 包

1. 保持进程存在。
2. 不点击 App 图标，等待归因、cloak、referrer 回调。
3. Adjust 确认出现 `request adjust result`；AF 确认出现
   `af native headless logSession called` 和 `request af result`。
4. 确认 `refreshBUserState()` 变成 B。
5. 确认 `startMediaNotificationsIfNeeded()` 被再次调用。
6. 确认媒体通知注册成功。

### 场景 4：后台 Engine 已存在后打开 App

1. 先通过 YouTube 创建后台 Engine。
2. 点击桌面图标。
3. 确认只出现 `reuseEngine`，没有第二次 `createEngine`。
4. 确认启动页和首页正常显示。
5. 确认 install 没有重复成功上报，Headless 失败时允许 UI 重试。
6. 确认打开 UI 后才出现宿主自己的业务 session 请求；归因 SDK Session 不受此条限制。
7. Adjust 确认模拟生命周期已交接；AF 不需要生命周期交接。
8. 确认通知点击、悬浮窗点击、广告和页面跳转正常，且没有重复触发两次。

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

### 场景 9：划掉任务后重建 Engine

仅在开启 `destroy_engine_when_ui_destroyed` 时测试：

1. 启动 App 并连续进入几个二级页面，例如文件选择结果页。
2. 从最近任务中划掉 App。
3. 日志应出现 `destroyEngine source=flutter_activity_on_destroy` 和 `destroyEngine complete`。
4. 再次点击 App 图标。
5. 日志应出现新的 `createEngine source=flutter_activity requestUi=true`，而不是复用旧 Engine。
6. 确认重新执行 Dart entrypoint、后台初始化和 UI 初始化，并从启动页按宿主代码进入下一页。
7. 再次划掉 App，然后打开会触发媒体路由发现的外部应用。
8. 确认 Service 的 `onCreate`、`onBind` 或 `onStartCommand` 能重新创建 Engine，并且进程内没有第二个 Engine。

## 十二、重要限制和注意事项

### 1. 强行停止后无法保证拉起

如果用户在系统设置中点击“强行停止”，Android 通常会阻止该应用的 Service、Receiver、Alarm 和 Job 再次运行，直到用户主动打开 App。插件不能绕过系统限制。

### 2. FlutterEngine 常驻不等于进程永远存活

默认配置下插件不会主动调用 `FlutterEngine.destroy()`；开启 `destroy_engine_when_ui_destroyed` 后，非配置变更导致的 UI Activity 销毁会主动销毁 Engine。FlutterEngine 本身不会提高 Android 进程优先级，系统内存不足时仍然可以杀掉进程。

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

插件 Android `minSdk` 是 26。宿主 App 的 `minSdk` 不能低于 26。

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
App自己的 Adjust 或 AF 工具类
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
可选的 Activity 销毁时 Engine 重建策略
Android/Dart 握手
UI 请求防丢失
后台/UI 初始化只执行一次
R8 keep rules
统一调试日志
```

以后插件逻辑有调整时，只升级公共插件，不要把 Service 和 Engine Manager 复制到每个 App 中。
