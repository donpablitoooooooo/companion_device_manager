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
    private var activeEngine: FlutterEngine? = null
    private var backgroundChannel: MethodChannel? = null
    private var pendingEventPayload: Map<String, Any?>? = null
    private var pendingCallbackHandle: Long? = null

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

        Log.d(tag, "Found registered callback handle=$callbackHandle dispatcherHandle=$dispatcherHandle")
        runOnMainThread {
            startBackgroundCallbackEngine(context, dispatcherHandle, callbackHandle, payload)
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post(block)
        }
    }

    private fun startBackgroundCallbackEngine(
        context: Context,
        dispatcherHandle: Long,
        callbackHandle: Long,
        eventPayload: Map<String, Any?>,
    ) {
        activeEngine?.destroy()
        activeEngine = null
        backgroundChannel = null

        pendingEventPayload = eventPayload
        pendingCallbackHandle = callbackHandle

        val callbackInfo = FlutterCallbackInformation.lookupCallbackInformation(dispatcherHandle)
            ?: run {
                Log.e(tag, "Unable to resolve Flutter callback info for dispatcherHandle=$dispatcherHandle")
                return
            }

        val flutterLoader: FlutterLoader = FlutterInjector.instance().flutterLoader()
        flutterLoader.startInitialization(context)
        flutterLoader.ensureInitializationComplete(context, null)

        val engine = FlutterEngine(context)
        activeEngine = engine

        val channel = MethodChannel(engine.dartExecutor.binaryMessenger, BACKGROUND_CHANNEL_NAME)
        backgroundChannel = channel
        channel.setMethodCallHandler { call: MethodCall, result: MethodChannel.Result ->
            if (call.method == "backgroundDispatcherInitialized") {
                dispatchPendingEventToDart()
                result.success(null)
            } else {
                result.notImplemented()
            }
        }


        val dartCallback = DartExecutor.DartCallback(
            context.assets,
            flutterLoader.findAppBundlePath(),
            callbackInfo,
        )
        engine.dartExecutor.executeDartCallback(dartCallback)
        Log.d(tag, "Executed Dart background dispatcher callback")
    }

    private fun dispatchPendingEventToDart() {
        val channel = backgroundChannel
        val eventPayload = pendingEventPayload
        val callbackHandle = pendingCallbackHandle
        if (channel == null || eventPayload == null || callbackHandle == null) {
            Log.w(tag, "Skipping event dispatch because payload, handle, or channel is missing")
            return
        }

        channel.invokeMethod(
            "dispatchBackgroundEvent",
            mapOf<String, Any?>(
                "event" to eventPayload,
                "callbackHandle" to callbackHandle,
            ),
        )
        pendingEventPayload = null
        pendingCallbackHandle = null
        Log.d(tag, "Delivered background event payload to Dart dispatcher")
    }

    override fun onDestroy() {
        backgroundChannel?.setMethodCallHandler(null)
        backgroundChannel = null
        pendingEventPayload = null
        pendingCallbackHandle = null
        activeEngine?.destroy()
        activeEngine = null
        Log.d(tag, "Service onDestroy called")
        super.onDestroy()
    }

    companion object {
        private const val BACKGROUND_CHANNEL_NAME = "companion_device_manager/background"
    }
}

