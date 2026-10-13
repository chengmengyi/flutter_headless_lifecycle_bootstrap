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
        ensureEngine("lifecycle_service_on_create")
    }

    private fun ensureEngine(source: String) {
        runCatching {
            PersistentFlutterEngineManager.getOrCreateEngine(
                context = applicationContext,
                requestUi = false,
                source = source,
            )
        }.onFailure {
            Log.e(TAG, "service failed to ensure FlutterEngine source=$source", it)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "serviceOnBind action=${intent?.action}")
        ensureEngine("lifecycle_service_on_bind")
        return null
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Log.d(TAG, "serviceOnStartCommand action=${intent?.action}")
        ensureEngine("lifecycle_service_on_start_command")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "serviceOnDestroy engine retained")
        super.onDestroy()
    }
}
