package com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap

import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel

/** Owns the single FlutterEngine used by both the lifecycle service and UI. */
internal object PersistentFlutterEngineManager {
    private const val TAG = "HeadlessLifecycle"
    private const val DEFAULT_DART_ENTRYPOINT = "headlessLifecycleMain"
    private const val META_DATA_DART_ENTRYPOINT =
        "flutter_headless_lifecycle_bootstrap.dart_entrypoint"
    private const val META_DATA_DART_LIBRARY =
        "flutter_headless_lifecycle_bootstrap.dart_library"
    private const val META_DATA_DESTROY_ENGINE_WHEN_UI_DESTROYED =
        "flutter_headless_lifecycle_bootstrap.destroy_engine_when_ui_destroyed"

    private var flutterEngine: FlutterEngine? = null
    private var coordinationChannel: MethodChannel? = null
    private var dartBootstrapReady = false
    private var uiRequested = false
    private var engineCreationSource: String? = null

    @Synchronized
    fun getOrCreateEngine(
        context: Context,
        requestUi: Boolean,
        source: String,
    ): FlutterEngine {
        checkMainThread()
        if (requestUi) {
            uiRequested = true
        }

        flutterEngine?.let { existingEngine ->
            Log.d(
                TAG,
                "reuseEngine source=$source requestUi=$requestUi " +
                    "createdBy=$engineCreationSource dartReady=$dartBootstrapReady",
            )
            dispatchUiRequestIfReady()
            return existingEngine
        }

        val appContext = context.applicationContext
        val entrypointName = readMetaDataString(appContext, META_DATA_DART_ENTRYPOINT)
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DART_ENTRYPOINT
        val entrypointLibrary = readMetaDataString(appContext, META_DATA_DART_LIBRARY)
            ?.takeIf { it.isNotBlank() }

        Log.d(
            TAG,
            "createEngine source=$source requestUi=$requestUi " +
                "entrypoint=$entrypointName library=${entrypointLibrary ?: "main.dart"}",
        )

        var newEngine: FlutterEngine? = null
        try {
            newEngine = FlutterEngine(appContext)
            flutterEngine = newEngine
            engineCreationSource = source

            val flutterLoader = FlutterInjector.instance().flutterLoader()
            val appBundlePath = flutterLoader.findAppBundlePath()
            val dartEntrypoint =
                if (entrypointLibrary == null) {
                    DartExecutor.DartEntrypoint(appBundlePath, entrypointName)
                } else {
                    DartExecutor.DartEntrypoint(
                        appBundlePath,
                        entrypointLibrary,
                        entrypointName,
                    )
                }
            newEngine.dartExecutor.executeDartEntrypoint(dartEntrypoint)
            return newEngine
        } catch (error: Throwable) {
            Log.e(TAG, "createEngine failed source=$source", error)
            coordinationChannel = null
            dartBootstrapReady = false
            engineCreationSource = null
            flutterEngine = null
            runCatching { newEngine?.destroy() }
            throw error
        }
    }

    @Synchronized
    fun requestUi(source: String) {
        checkMainThread()
        uiRequested = true
        Log.d(TAG, "requestUi source=$source dartReady=$dartBootstrapReady")
        dispatchUiRequestIfReady()
    }

    @Synchronized
    fun destroyEngineWhenUiHostDestroyed(
        context: Context,
        source: String,
    ) {
        checkMainThread()
        if (!readMetaDataBoolean(
                context.applicationContext,
                META_DATA_DESTROY_ENGINE_WHEN_UI_DESTROYED,
            )
        ) {
            Log.d(TAG, "retainEngine source=$source reason=manifest_config_disabled")
            return
        }

        val engineToDestroy = flutterEngine
        if (engineToDestroy == null) {
            Log.d(TAG, "destroyEngine skipped source=$source reason=no_engine")
            resetEngineState()
            return
        }

        Log.d(
            TAG,
            "destroyEngine source=$source createdBy=$engineCreationSource " +
                "dartReady=$dartBootstrapReady uiRequested=$uiRequested",
        )
        resetEngineState()
        runCatching {
            engineToDestroy.destroy()
        }.onSuccess {
            Log.d(TAG, "destroyEngine complete source=$source")
        }.onFailure { error ->
            Log.e(TAG, "destroyEngine failed source=$source", error)
        }
    }

    @Synchronized
    fun onPluginAttached(channel: MethodChannel) {
        coordinationChannel = channel
        Log.d(TAG, "pluginAttached engineExists=${flutterEngine != null}")
    }

    @Synchronized
    fun onPluginDetached(channel: MethodChannel) {
        if (coordinationChannel === channel) {
            coordinationChannel = null
            dartBootstrapReady = false
        }
        Log.d(TAG, "pluginDetached engineExists=${flutterEngine != null}")
    }

    @Synchronized
    fun onDartBootstrapReady(): Map<String, Any?> {
        dartBootstrapReady = true
        Log.d(
            TAG,
            "dartBootstrapReady uiRequested=$uiRequested createdBy=$engineCreationSource",
        )
        return currentState()
    }

    @Synchronized
    fun onDartPhase(
        phase: String,
        error: String?,
    ) {
        if (error.isNullOrBlank()) {
            Log.d(TAG, "dartPhase phase=$phase")
        } else {
            Log.e(TAG, "dartPhase phase=$phase error=$error")
        }
    }

    @Synchronized
    fun currentState(): Map<String, Any?> =
        mapOf(
            "engineExists" to (flutterEngine != null),
            "dartBootstrapReady" to dartBootstrapReady,
            "uiRequested" to uiRequested,
            "engineCreationSource" to engineCreationSource,
        )

    private fun dispatchUiRequestIfReady() {
        if (!uiRequested || !dartBootstrapReady) {
            return
        }
        coordinationChannel?.invokeMethod(
            "requestUi",
            mapOf("source" to "android"),
        )
    }

    private fun resetEngineState() {
        flutterEngine = null
        coordinationChannel = null
        dartBootstrapReady = false
        uiRequested = false
        engineCreationSource = null
    }

    @Suppress("DEPRECATION")
    private fun readMetaDataString(
        context: Context,
        key: String,
    ): String? =
        runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString(key)
        }.onFailure {
            Log.w(TAG, "readMetaData failed key=$key", it)
        }.getOrNull()

    @Suppress("DEPRECATION")
    private fun readMetaDataBoolean(
        context: Context,
        key: String,
    ): Boolean =
        runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getBoolean(key, false)
                ?: false
        }.onFailure {
            Log.w(TAG, "readMetaData failed key=$key", it)
        }.getOrDefault(false)

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "PersistentFlutterEngineManager must be called on Android's main thread."
        }
    }
}
