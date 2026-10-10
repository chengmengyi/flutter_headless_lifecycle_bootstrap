package com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap

import android.content.Context
import android.content.Intent
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine

/** FragmentActivity variant for apps whose plugins require FragmentActivity. */
open class LifecycleFlutterFragmentActivity : FlutterFragmentActivity() {
    override fun provideFlutterEngine(context: Context): FlutterEngine =
        PersistentFlutterEngineManager.getOrCreateEngine(
            context = context,
            requestUi = true,
            source = "flutter_fragment_activity",
        )

    override fun shouldDestroyEngineWithHost(): Boolean = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        PersistentFlutterEngineManager.requestUi("flutter_fragment_activity_new_intent")
    }
}
