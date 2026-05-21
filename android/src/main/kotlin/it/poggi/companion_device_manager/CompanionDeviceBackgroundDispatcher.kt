package it.poggi.companion_device_manager

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.FlutterCallbackInformation

/**
 * Owns the long-lived headless FlutterEngine used to deliver CDM background
 * events to Dart. The engine is created lazily on the first event and kept
 * alive across subsequent events so we never tear it down mid-callback (which
 * would silently abort in-flight work like flutter_local_notifications.show()).
 *
 * Events are queued until the Dart side reports it is ready, then they are
 * delivered through [DISPATCH_CHANNEL] one by one. The Dart callback receives
 * each event payload directly, so it doesn't have to race against
 * `getLastBackgroundEvent`.
 */
internal object CompanionDeviceBackgroundDispatcher {
    private const val TAG = "CDMBgDispatcher"
    private const val DISPATCH_CHANNEL = "companion_device_manager/background_dispatch"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: FlutterEngine? = null
    private var dispatchChannel: MethodChannel? = null
    private var isReady = false
    private val pendingEvents = ArrayDeque<Map<String, Any?>>()

    /** True while the dispatcher is bootstrapping its Dart isolate. */
    @Volatile
    var isBackgroundContext: Boolean = false
        private set

    fun dispatchEvent(context: Context, callbackHandle: Long, payload: Map<String, Any?>) {
        runOnMain {
            when {
                engine == null -> {
                    Log.d(TAG, "No engine yet, queueing event and starting engine")
                    pendingEvents.addLast(payload)
                    startEngine(context.applicationContext, callbackHandle)
                }
                !isReady -> {
                    Log.d(TAG, "Engine starting, queueing event (queue size=${pendingEvents.size + 1})")
                    pendingEvents.addLast(payload)
                }
                else -> sendEvent(payload)
            }
        }
    }

    /**
     * Tears down the background engine. Called from the plugin when the user
     * clears the registered callback handle.
     */
    fun shutdown() {
        runOnMain {
            dispatchChannel?.setMethodCallHandler(null)
            dispatchChannel = null
            engine?.destroy()
            engine = null
            isReady = false
            isBackgroundContext = false
            pendingEvents.clear()
            Log.d(TAG, "Background engine shut down")
        }
    }

    private fun startEngine(appContext: Context, callbackHandle: Long) {
        val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(callbackHandle)
        if (callbackInfo == null) {
            Log.e(TAG, "Unable to resolve callback info for handle=$callbackHandle; dropping pending events")
            pendingEvents.clear()
            return
        }

        val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(appContext)
        loader.ensureInitializationComplete(appContext, null)

        // Mark this thread/process as bootstrapping the background engine BEFORE
        // the engine (and therefore its plugins) come up. The flag stays on
        // until Dart sends "ready" - that's the point at which we know plugin
        // attachment has completed inside the headless engine and we can stop
        // suppressing presence-observation auto-starts in the plugin.
        isBackgroundContext = true

        val newEngine = FlutterEngine(appContext)
        engine = newEngine

        // Register all native plugins on the headless engine via the generated
        // registrant. Plugins that also declare a `dartPluginClass` will get a
        // second registration attempt from `DartPluginRegistrant.ensureInitialized()`
        // in the Dart entrypoint; the framework logs a warning on the duplicate
        // but no-ops gracefully. Skipping this call would leave native-only
        // plugins (like this one) without their channel handlers attached.
        runCatching {
            Class.forName("io.flutter.plugins.GeneratedPluginRegistrant")
                .getDeclaredMethod("registerWith", FlutterEngine::class.java)
                .invoke(null, newEngine)
        }.onFailure { error ->
            Log.w(TAG, "GeneratedPluginRegistrant unavailable on background engine", error)
        }

        // Set up the dispatch channel BEFORE the Dart entrypoint starts so we
        // can answer the "ready" call as soon as Dart attaches its handler.
        val channel = MethodChannel(newEngine.dartExecutor.binaryMessenger, DISPATCH_CHANNEL)
        dispatchChannel = channel
        channel.setMethodCallHandler { call, result ->
            when (call.method) {
                "ready" -> {
                    Log.d(TAG, "Dart side ready, flushing ${pendingEvents.size} pending event(s)")
                    isReady = true
                    isBackgroundContext = false
                    while (pendingEvents.isNotEmpty()) {
                        sendEvent(pendingEvents.removeFirst())
                    }
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        val dartCallback = DartExecutor.DartCallback(
            appContext.assets,
            loader.findAppBundlePath(),
            callbackInfo,
        )
        newEngine.dartExecutor.executeDartCallback(dartCallback)
        Log.d(TAG, "Started persistent background engine and executed Dart entrypoint")
    }

    private fun sendEvent(payload: Map<String, Any?>) {
        Log.d(TAG, "Dispatching event to Dart: type=${payload["type"]}")
        dispatchChannel?.invokeMethod("onEvent", payload)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
