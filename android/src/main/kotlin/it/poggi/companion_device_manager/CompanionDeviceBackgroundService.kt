package it.poggi.companion_device_manager

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import android.util.Log

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
        val mac = associationInfo.deviceMacAddress?.toString()?.lowercase()
        if (mac != null && isDuplicate(mac, type)) {
            Log.d(tag, "Skipping duplicate event type=$type mac=$mac (within ${DEDUPE_WINDOW_MS}ms)")
            return
        }

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
        if (callbackHandle == null) {
            Log.w(tag, "No registered background callback handle; event will not execute Dart callback")
            return
        }
        Log.d(tag, "Dispatching to background engine, callbackHandle=$callbackHandle")
        CompanionDeviceBackgroundDispatcher.dispatchEvent(context, callbackHandle, payload)
    }

    override fun onDestroy() {
        Log.d(tag, "Service onDestroy called")
        super.onDestroy()
        // NOTE: do NOT tear down the dispatcher here. The system can recreate
        // this service for the next event, but the dispatcher's FlutterEngine
        // is a process-level singleton we want to keep alive across rebinds.
    }

    companion object {
        private const val DEDUPE_WINDOW_MS = 5_000L

        // The system may have multiple CDM associations for the same physical
        // device (e.g. the user re-associated without removing the previous
        // entry). Each association fires its own onDeviceAppeared /
        // onDeviceDisappeared callback, which causes flutter_local_notifications
        // to receive two `notify()` calls in quick succession against the same
        // notification id - the second one quietly updates the first and
        // suppresses the heads-up. Collapse those duplicates by tracking the
        // last (mac, type) we forwarded and skipping anything that arrives
        // within DEDUPE_WINDOW_MS for the same pair.
        //
        // Static because the service instance can be torn down between events.
        private val lastEventByMac = mutableMapOf<String, Pair<String, Long>>()

        private fun isDuplicate(mac: String, type: String): Boolean = synchronized(lastEventByMac) {
            val now = System.currentTimeMillis()
            val last = lastEventByMac[mac]
            val duplicate = last != null && last.first == type && (now - last.second) < DEDUPE_WINDOW_MS
            if (!duplicate) lastEventByMac[mac] = type to now
            duplicate
        }
    }
}
