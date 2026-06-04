package it.poggi.companion_device_manager

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.view.FlutterCallbackInformation
import io.flutter.FlutterInjector
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

class CompanionDeviceBackgroundService : CompanionDeviceService() {
    private val tag = "CDMBackgroundService"

    override fun onCreate() {
        super.onCreate()
        Log.d(tag, "Service onCreate called - service is alive")
    }

    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        Log.d(tag, "onDeviceAppeared id=${associationInfo.id} mac=${associationInfo.deviceMacAddress}")
        handleDeviceEvent("device_appeared", associationInfo)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        Log.d(tag, "onDeviceDisappeared id=${associationInfo.id} mac=${associationInfo.deviceMacAddress}")
        handleDeviceEvent("device_disappeared", associationInfo)
    }

    private fun handleDeviceEvent(type: String, associationInfo: AssociationInfo) {
        val context = applicationContext
        val payload = mapOf<String, Any?>(
            "type" to type,
            "timestampMs" to System.currentTimeMillis(),
            "association" to mapOf<String, Any?>(
                "associationId" to associationInfo.id,
                "macAddress" to associationInfo.deviceMacAddress?.toString(),
                "displayName" to associationInfo.displayName?.toString(),
                "deviceProfile" to associationInfo.deviceProfile,
                "selfManaged" to associationInfo.isSelfManaged,
                "lastTimeConnectedMs" to null,
            ),
            "rawPayload" to mapOf<String, Any?>(
                "type" to type,
            ),
        )

        CompanionDeviceStorage.persistEvent(context, payload)
        CompanionDeviceEventStream.emit(payload)
        Log.d(tag, "Persisted and emitted event type=$type")

        val callbackHandle = CompanionDeviceStorage.getBackgroundCallbackHandle(context)
        val dispatcherHandle = CompanionDeviceStorage.getBackgroundDispatcherHandle(context)
        if (callbackHandle == null) {
            Log.w(tag, "No registered background callback handle; event will not execute Dart callback")
            return
        }
        if (dispatcherHandle == null) {
            Log.e(tag, "Missing background dispatcher handle. Re-register callback from Dart.")
            return
        }

        Log.d(tag, "Dispatching to persistent background engine, callbackHandle=$callbackHandle dispatcherHandle=$dispatcherHandle")
        BackgroundEngine.dispatch(context.applicationContext, dispatcherHandle, callbackHandle, payload)
    }

    override fun onDestroy() {
        // IMPORTANT: do NOT destroy the FlutterEngine here. The system unbinds
        // this CompanionDeviceService almost immediately after delivering an
        // event (see "Unbinding ..." in logcat) and rebinds it for the next
        // one. Tearing the engine down would kill the Dart callback (e.g.
        // flutter_local_notifications.show()) mid-flight on a cold start - which
        // is exactly why the notification never appeared when the app was
        // killed. The engine is a process-level singleton kept alive in
        // BackgroundEngine until the callback is cleared or the process dies.
        Log.d(tag, "Service onDestroy called (engine kept alive)")
        super.onDestroy()
    }
}

/**
 * Process-level owner of the headless [FlutterEngine] used to deliver CDM
 * background events to Dart.
 *
 * The engine is created lazily on the first event and kept alive across service
 * rebinds, so we never tear it down mid-callback. Events are queued until the
 * Dart dispatcher reports it is ready ("backgroundDispatcherInitialized"), then
 * delivered one by one through [CHANNEL_NAME] as `dispatchBackgroundEvent`.
 */
internal object BackgroundEngine {
    private const val TAG = "CDMBgEngine"
    private const val CHANNEL_NAME = "companion_device_manager/background"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: FlutterEngine? = null
    private var channel: MethodChannel? = null
    private var ready = false
    private var callbackHandle: Long = 0L
    private val pending = ArrayDeque<Map<String, Any?>>()

    fun dispatch(
        appContext: Context,
        dispatcherHandle: Long,
        callbackHandle: Long,
        payload: Map<String, Any?>,
    ) {
        runOnMain {
            this.callbackHandle = callbackHandle
            when {
                engine == null -> {
                    Log.d(TAG, "No engine yet - queueing event and starting persistent engine")
                    pending.addLast(payload)
                    start(appContext, dispatcherHandle)
                }
                !ready -> {
                    Log.d(TAG, "Engine starting - queueing event (queue=${pending.size + 1})")
                    pending.addLast(payload)
                }
                else -> send(payload)
            }
        }
    }

    /** Tears down the engine. Called when the registered callback is cleared. */
    fun shutdown() {
        runOnMain {
            channel?.setMethodCallHandler(null)
            channel = null
            engine?.destroy()
            engine = null
            ready = false
            pending.clear()
            Log.d(TAG, "Persistent background engine shut down")
        }
    }

    private fun start(appContext: Context, dispatcherHandle: Long) {
        // Load + initialise the Flutter native library BEFORE the JNI callback
        // lookup. On a cold start libflutter.so is not loaded yet, and
        // lookupCallbackInformation() is a native call - doing it first throws
        // UnsatisfiedLinkError and crashes the process.
        val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(appContext)
        loader.ensureInitializationComplete(appContext, null)

        val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(dispatcherHandle)
        if (callbackInfo == null) {
            Log.e(TAG, "Unable to resolve dispatcher callback for handle=$dispatcherHandle; dropping ${pending.size} event(s)")
            pending.clear()
            return
        }

        val newEngine = FlutterEngine(appContext)
        engine = newEngine

        // Register the app's plugins on this headless engine so the Dart
        // callback can use them (e.g. flutter_local_notifications) on a cold
        // start. Reflection, because the generated registrant lives in the host
        // app, not in this plugin.
        runCatching {
            Class.forName("io.flutter.plugins.GeneratedPluginRegistrant")
                .getDeclaredMethod("registerWith", FlutterEngine::class.java)
                .invoke(null, newEngine)
        }.onFailure { error ->
            Log.w(TAG, "GeneratedPluginRegistrant unavailable on background engine", error)
        }

        val newChannel = MethodChannel(newEngine.dartExecutor.binaryMessenger, CHANNEL_NAME)
        channel = newChannel
        newChannel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            if (call.method == "backgroundDispatcherInitialized") {
                Log.d(TAG, "Dart dispatcher ready - flushing ${pending.size} pending event(s)")
                ready = true
                while (pending.isNotEmpty()) {
                    send(pending.removeFirst())
                }
                result.success(null)
            } else {
                result.notImplemented()
            }
        }

        newEngine.dartExecutor.executeDartCallback(
            DartExecutor.DartCallback(appContext.assets, loader.findAppBundlePath(), callbackInfo),
        )
        Log.d(TAG, "Started persistent background engine and executed Dart dispatcher entrypoint")
    }

    private fun send(payload: Map<String, Any?>) {
        Log.d(TAG, "Dispatching event to Dart: type=${payload["type"]}")
        channel?.invokeMethod(
            "dispatchBackgroundEvent",
            mapOf<String, Any?>(
                "event" to payload,
                "callbackHandle" to callbackHandle,
            ),
        )
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
