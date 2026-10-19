package com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** Persists whether this installation has ever created a real UI Activity. */
internal object FirstUiLaunchGate {
    private const val TAG = "HeadlessLifecycle"
    private const val META_DATA_REQUIRE_FIRST_UI_LAUNCH =
        "flutter_headless_lifecycle_bootstrap.require_first_ui_launch"
    private const val PREFERENCES_NAME =
        "flutter_headless_lifecycle_bootstrap.preferences"
    private const val KEY_HAS_LAUNCHED_UI = "has_launched_ui"

    fun markUiLaunched(context: Context) {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        if (preferences.getBoolean(KEY_HAS_LAUNCHED_UI, false)) {
            return
        }
        val persisted = preferences
            .edit()
            .putBoolean(KEY_HAS_LAUNCHED_UI, true)
            .commit()
        Log.d(TAG, "firstUiLaunchGate marked UI launched persisted=$persisted")
    }

    fun canStartHeadlessly(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!requiresFirstUiLaunch(appContext)) {
            return true
        }
        val hasLaunchedUi = appContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_HAS_LAUNCHED_UI, false)
        if (!hasLaunchedUi) {
            Log.d(TAG, "headless start blocked reason=first_ui_not_launched")
        }
        return hasLaunchedUi
    }

    @Suppress("DEPRECATION")
    private fun requiresFirstUiLaunch(context: Context): Boolean =
        runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getBoolean(META_DATA_REQUIRE_FIRST_UI_LAUNCH, false)
                ?: false
        }.onFailure {
            Log.w(TAG, "readMetaData failed key=$META_DATA_REQUIRE_FIRST_UI_LAUNCH", it)
        }.getOrDefault(false)
}
