package com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Exported discovery service used to start the host application's long-lived
 * FlutterEngine without launching an Activity.
 */
class HeadlessLifecycleService : Service() {
    companion object {
        private const val TAG = "HeadlessLifecycle"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "serviceOnCreate")
        runCatching {
            PersistentFlutterEngineManager.getOrCreateEngine(
                context = applicationContext,
                requestUi = false,
                source = "lifecycle_service_on_create",
            )
        }.onFailure {
            Log.e(TAG, "service failed to create FlutterEngine", it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "serviceOnBind action=${intent?.action}")
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "serviceOnStartCommand action=${intent?.action}")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "serviceOnDestroy engine retained")
        super.onDestroy()
    }
}
