package com.flutter.headless.bootstrap.flutter_headless_lifecycle_bootstrap

import android.os.Build
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/** FlutterHeadlessLifecycleBootstrapPlugin */
class FlutterHeadlessLifecycleBootstrapPlugin :
    FlutterPlugin,
    MethodCallHandler {
    companion object {
        const val CHANNEL_NAME =
            "flutter_headless_lifecycle_bootstrap/bootstrap"
    }

    private lateinit var channel: MethodChannel

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, CHANNEL_NAME)
        channel.setMethodCallHandler(this)
        PersistentFlutterEngineManager.onPluginAttached(channel)
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result
    ) {
        when (call.method) {
            "getPlatformVersion" -> result.success("Android ${Build.VERSION.RELEASE}")
            "bootstrapReady" ->
                result.success(PersistentFlutterEngineManager.onDartBootstrapReady())
            "reportDartPhase" -> {
                val phase = call.argument<String>("phase").orEmpty()
                val error = call.argument<String>("error")
                PersistentFlutterEngineManager.onDartPhase(phase, error)
                result.success(null)
            }
            "getNativeBootstrapState" ->
                result.success(PersistentFlutterEngineManager.currentState())
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        PersistentFlutterEngineManager.onPluginDetached(channel)
        channel.setMethodCallHandler(null)
    }
}
