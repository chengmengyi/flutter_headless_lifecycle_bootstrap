package com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/** FlutterActivity that reuses the process-wide engine created by the service. */
open class LifecycleFlutterActivity : FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        FirstUiLaunchGate.markUiLaunched(this)
        super.onCreate(savedInstanceState)
    }

    override fun provideFlutterEngine(context: Context): FlutterEngine =
        PersistentFlutterEngineManager.getOrCreateEngine(
            context = context,
            requestUi = true,
            source = "flutter_activity",
        )

    override fun shouldDestroyEngineWithHost(): Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PersistentFlutterEngineManager.requestUi("flutter_activity_new_intent")
    }

    override fun onDestroy() {
        val shouldHandleUiHostDestruction = !isChangingConfigurations
        super.onDestroy()
        if (shouldHandleUiHostDestruction) {
            PersistentFlutterEngineManager.destroyEngineWhenUiHostDestroyed(
                context = applicationContext,
                source = "flutter_activity_on_destroy",
            )
        }
    }
}
