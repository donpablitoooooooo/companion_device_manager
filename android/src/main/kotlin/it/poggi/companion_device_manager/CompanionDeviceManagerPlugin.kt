package it.poggi.companion_device_manager

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.ObservingDevicePresenceRequest
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.Executors

class CompanionDeviceManagerPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware {
    private val tag = "CDMPlugin"
    private var applicationContext: Context? = null
    private var activity: Activity? = null
    private var activityBinding: ActivityPluginBinding? = null
    private lateinit var channel: MethodChannel
    private lateinit var eventsChannel: EventChannel
    private val eventStreamOwner = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundExecutor = Executors.newSingleThreadExecutor()
    private var pendingAssociationResult: Result? = null
    private var pendingAssociationRequest: AssociationRequest? = null
    private var pendingAssociationDisplayName: String? = null
    private var pendingAssociationMacAddress: String? = null

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        applicationContext = flutterPluginBinding.applicationContext
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "companion_device_manager")
        channel.setMethodCallHandler(this)
        eventsChannel = EventChannel(flutterPluginBinding.binaryMessenger, "companion_device_manager/events")
        eventsChannel.setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    CompanionDeviceEventStream.attachSink(eventStreamOwner, events)
                }

                override fun onCancel(arguments: Any?) {
                    CompanionDeviceEventStream.detachSink(eventStreamOwner)
                }
            },
        )

        if (CompanionDeviceStorage.getBackgroundCallbackHandle(applicationContext) != null) {
            schedulePresenceObservationStart("engine_attached")
        }
    }

    override fun onMethodCall(
        call: MethodCall,
        result: Result
    ) {
        when (call.method) {
            "isAvailable" -> result.success(isCompanionDeviceManagerAvailable())
            "getAssociations" -> result.success(readAssociations())
            "associate" -> startAssociation(call, result)
            "disassociate" -> disassociate(call, result)
            "registerBackgroundCallback" -> registerBackgroundCallback(call, result)
            "clearBackgroundCallback" -> clearBackgroundCallback(result)
            "getLastBackgroundEvent" -> result.success(CompanionDeviceStorage.getLastEventMap(applicationContext))
            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        eventsChannel.setStreamHandler(null)
        CompanionDeviceEventStream.detachSink(eventStreamOwner)
        applicationContext = null
        backgroundExecutor.shutdown()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
        activityBinding = binding
        binding.addActivityResultListener { requestCode, resultCode, data ->
            handleActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        onAttachedToActivity(binding)
    }

    override fun onDetachedFromActivity() {
        activity = null
        activityBinding = null
    }

    private fun isCompanionDeviceManagerAvailable(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
    }

    private fun getManager(): CompanionDeviceManager {
        val context = applicationContext ?: throw IllegalStateException("Plugin not attached to an application context.")
        return context.getSystemService(CompanionDeviceManager::class.java)
    }

    private fun readAssociations(): List<Map<String, Any?>> {
        if (!isCompanionDeviceManagerAvailable()) {
            Log.w(tag, "CDM not available on this device")
            return emptyList()
        }

        return getManager().associations.map { address ->
            Log.d(tag, "Found association: $address")
            mapOf<String, Any?>(
                "associationId" to null,
                "macAddress" to address,
                "displayName" to null,
                "deviceProfile" to null,
                "selfManaged" to false,
                "lastTimeConnectedMs" to null,
            )
        }
    }

    private fun startAssociation(call: MethodCall, result: Result) {
        Log.d(tag, "startAssociation called")
        if (!isCompanionDeviceManagerAvailable()) {
            result.error("cdm_unavailable", "Companion Device Manager is only available on Android 8.0 (API 26) or newer.", null)
            return
        }

        val currentActivity = activity
        if (currentActivity == null) {
            result.error("no_activity", "An activity is required to launch the CDM chooser.", null)
            return
        }

        if (pendingAssociationResult != null) {
            result.error("association_in_progress", "A companion device association is already in progress.", null)
            return
        }

        val associationRequest = buildAssociationRequest(call.arguments as? Map<*, *>)
        val manager = getManager()

        pendingAssociationResult = result
        pendingAssociationRequest = associationRequest.request
        pendingAssociationDisplayName = associationRequest.displayName
        pendingAssociationMacAddress = associationRequest.macAddress

        try {
            manager.associate(
                associationRequest.request,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: IntentSender) {
                        Log.d(tag, "CDM chooser intent received from system")
                        try {
                            currentActivity.startIntentSenderForResult(
                                chooserLauncher,
                                REQUEST_ASSOCIATE,
                                null,
                                0,
                                0,
                                0,
                            )
                        } catch (exception: IntentSender.SendIntentException) {
                            finishPendingAssociationError(
                                "chooser_launch_failed",
                                "Unable to launch the CDM chooser.",
                                exception,
                            )
                        }
                    }

                    override fun onFailure(error: CharSequence?) {
                        Log.e(tag, "CDM associate callback failure: ${error ?: "unknown"}")
                        finishPendingAssociationError(
                            "association_failed",
                            error?.toString() ?: "The CDM association request failed.",
                            null,
                        )
                    }
                },
                null,
            )
        } catch (exception: Throwable) {
            finishPendingAssociationError("association_failed", exception.message ?: "The CDM association request failed.", exception)
        }
    }

    private fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQUEST_ASSOCIATE) {
            return false
        }

        Log.d(tag, "handleActivityResult for association requestCode=$requestCode resultCode=$resultCode")

        val pendingResult = pendingAssociationResult ?: return true
        val manager = applicationContext?.getSystemService(CompanionDeviceManager::class.java)

        if (resultCode == Activity.RESULT_OK) {
            val associations = readAssociations()
            Log.d(tag, "Association successful, ${associations.size} total associations now known to system")
            associations.forEach { assoc ->
                Log.d(tag, "  - Association: mac=${assoc["macAddress"]}")
            }
            val association = associations.firstOrNull { it["macAddress"] == pendingAssociationMacAddress }
                ?: associations.firstOrNull()
            val response = association ?: mapOf<String, Any?>(
                "associationId" to null,
                "macAddress" to pendingAssociationMacAddress,
                "displayName" to pendingAssociationDisplayName,
                "deviceProfile" to null,
                "selfManaged" to false,
                "lastTimeConnectedMs" to null,
            )
            schedulePresenceObservationStart("association_completed")
            pendingResult.success(response)
            CompanionDeviceStorage.persistEvent(
                applicationContext,
                mapOf(
                    "type" to "association_created",
                    "timestampMs" to System.currentTimeMillis(),
                    "association" to response,
                    "rawPayload" to mapOf<String, Any?>(
                        "resultCode" to resultCode,
                        "requestCode" to requestCode,
                    ),
                ),
            )
            CompanionDeviceEventStream.emit(
                mapOf(
                    "type" to "association_created",
                    "timestampMs" to System.currentTimeMillis(),
                    "association" to response,
                    "rawPayload" to mapOf<String, Any?>(
                        "resultCode" to resultCode,
                        "requestCode" to requestCode,
                    ),
                ),
            )
        } else {
            finishPendingAssociationError(
                "association_cancelled",
                "The CDM chooser was cancelled.",
                null,
            )
        }

        pendingAssociationResult = null
        pendingAssociationRequest = null
        pendingAssociationDisplayName = null
        pendingAssociationMacAddress = null
        return true
    }

    private data class AssociationRequestWithMetadata(
        val request: AssociationRequest,
        val displayName: String,
        val macAddress: String?,
    )

    private fun buildAssociationRequest(arguments: Map<*, *>?): AssociationRequestWithMetadata {
        val displayName = arguments?.get("displayName") as? String
        Log.d(tag, "buildAssociationRequest displayName=$displayName")
        if (displayName.isNullOrBlank()) {
            throw IllegalArgumentException("displayName is required.")
        }

        val builder = AssociationRequest.Builder()
            .setDisplayName(displayName)
        Log.d(tag, "Set display name: $displayName")

        val selfManaged = arguments?.get("selfManaged") as? Boolean ?: false
        if (selfManaged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(tag, "selfManaged=true, setting on builder")
            builder.setSelfManaged(true)
        }

        val singleDevice = arguments?.get("singleDevice") as? Boolean ?: true
        builder.setSingleDevice(singleDevice)

        val deviceProfile = arguments?.get("deviceProfile") as? String
        if (!deviceProfile.isNullOrBlank()) {
            builder.setDeviceProfile(deviceProfile)
        }

        var firstBluetoothAddress: String? = null
        val filters = arguments?.get("filters") as? List<*>
        Log.d(tag, "Processing ${filters?.size ?: 0} device filters")
        filters.orEmpty().filterIsInstance<Map<*, *>>().forEach { filterMap ->
            val type = filterMap["type"] as? String ?: throw IllegalArgumentException("Each filter must define a type.")
            Log.d(tag, "Processing filter type=$type")
            when (type) {
                "bluetooth" -> {
                    val address = (filterMap["address"] as? String)?.takeIf { it.isNotBlank() }
                    Log.d(tag, "Adding classic Bluetooth filter address=${address ?: "<any>"}")
                    if (address != null && firstBluetoothAddress == null) {
                        firstBluetoothAddress = address
                    }
                    val classicBuilder = BluetoothDeviceFilter.Builder()
                    if (address != null) {
                        classicBuilder.setAddress(address)
                    }
                    builder.addDeviceFilter(classicBuilder.build())
                }

                "bluetoothLe" -> {
                    val address = (filterMap["address"] as? String)?.takeIf { it.isNotBlank() }
                    Log.d(tag, "Adding BLE filter address=${address ?: "<any>"}")
                    if (address != null && firstBluetoothAddress == null) {
                        firstBluetoothAddress = address
                    }
                    val scanFilterBuilder = ScanFilter.Builder()
                    if (address != null) {
                        scanFilterBuilder.setDeviceAddress(address)
                    }
                    val filter = BluetoothLeDeviceFilter.Builder()
                        .setScanFilter(scanFilterBuilder.build())
                        .build()
                    Log.d(tag, "Created BluetoothLeDeviceFilter, adding to request")
                    builder.addDeviceFilter(filter)
                }

                else -> throw IllegalArgumentException("Unsupported device filter type: $type")
            }
        }
        Log.d(tag, "Final association request: displayName=$displayName filters=${filters?.size ?: 0} singleDevice=$singleDevice selfManaged=$selfManaged")

        return AssociationRequestWithMetadata(
            request = builder.build(),
            displayName = displayName,
            macAddress = firstBluetoothAddress,
        )
    }

    private fun disassociate(call: MethodCall, result: Result) {
        if (!isCompanionDeviceManagerAvailable()) {
            result.error("cdm_unavailable", "Companion Device Manager is only available on Android 8.0 (API 26) or newer.", null)
            return
        }

        val address = call.argument<String>("macAddress")
        if (address.isNullOrBlank()) {
            result.error("invalid_arguments", "macAddress is required to disassociate on this Android version.", null)
            return
        }

        try {
            getManager().disassociate(address)
            result.success(null)
        } catch (exception: Throwable) {
            result.error("disassociate_failed", exception.message, null)
        }
    }

    private fun registerBackgroundCallback(call: MethodCall, result: Result) {
        val handle = call.argument<Number>("callbackHandle")?.toLong()
        val dispatcherHandle = call.argument<Number>("dispatcherHandle")?.toLong()

        if (handle == null || handle == 0L || dispatcherHandle == null || dispatcherHandle == 0L) {
            result.error("invalid_arguments", "callbackHandle and dispatcherHandle are required.", null)
            return
        }

        Log.d(tag, "Registering background callback handle=$handle dispatcherHandle=$dispatcherHandle")
        CompanionDeviceStorage.storeBackgroundCallbackHandle(applicationContext, handle)
        CompanionDeviceStorage.storeBackgroundDispatcherHandle(applicationContext, dispatcherHandle)
        schedulePresenceObservationStart("callback_registered")
        result.success(null)
    }

    private fun schedulePresenceObservationStart(reason: String) {
        val context = applicationContext
        if (context == null) {
            Log.w(tag, "Skipping presence observation start for reason=$reason because plugin context is unavailable")
            return
        }

        backgroundExecutor.execute {
            runCatching {
                Log.d(tag, "Starting presence observation asynchronously for reason=$reason")
                startObservingPresenceForCurrentAssociations()
            }.onFailure { error ->
                Log.e(tag, "Failed to start presence observation for reason=$reason", error)
            }
        }
    }

    private fun clearBackgroundCallback(result: Result) {
        Log.d(tag, "Clearing background callback")
        stopObservingPresenceForCurrentAssociations()
        CompanionDeviceStorage.clearBackgroundCallbackHandle(applicationContext)
        CompanionDeviceStorage.clearBackgroundDispatcherHandle(applicationContext)
        result.success(null)
    }

    private fun startObservingPresenceForCurrentAssociations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(tag, "Presence observation API not available on SDK ${Build.VERSION.SDK_INT}")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startObservingPresenceByAssociationId()
            return
        }

        val associations = readAssociations()
        if (associations.isEmpty()) {
            Log.d(tag, "No associations available to start presence observation")
            return
        }

        associations.mapNotNull { it["macAddress"] as? String }
            .distinct()
            .forEach { address ->
                runCatching {
                    getManager().startObservingDevicePresence(address)
                }.onSuccess {
                    Log.d(tag, "Started observing presence for address=$address")
                }.onFailure { error ->
                    Log.e(tag, "Failed to start observing legacy presence for $address", error)
                }
            }
    }

    private fun stopObservingPresenceForCurrentAssociations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            stopObservingPresenceByAssociationId()
            return
        }

        readAssociations().mapNotNull { it["macAddress"] as? String }
            .distinct()
            .forEach { address ->
                runCatching {
                    getManager().stopObservingDevicePresence(address)
                }.onSuccess {
                    Log.d(tag, "Stopped observing presence for address=$address")
                }.onFailure { error ->
                    Log.w(tag, "Unable to stop observing legacy presence for $address", error)
                }
            }
    }

    private fun startObservingPresenceByAssociationId() {
        val myAssociations = readMyAssociations()
        if (myAssociations.isEmpty()) {
            Log.d(tag, "No associations available to start id-based presence observation")
            return
        }

        myAssociations.forEach { association ->
            val request = ObservingDevicePresenceRequest.Builder()
                .setAssociationId(association.id)
                .build()
            runCatching {
                getManager().startObservingDevicePresence(request)
            }.onSuccess {
                Log.d(tag, "Started observing presence for associationId=${association.id} mac=${association.deviceMacAddress}")
            }.onFailure { error ->
                Log.e(tag, "Failed to start observing presence for associationId=${association.id}", error)
            }
        }
    }

    private fun stopObservingPresenceByAssociationId() {
        readMyAssociations().forEach { association ->
            val request = ObservingDevicePresenceRequest.Builder()
                .setAssociationId(association.id)
                .build()
            runCatching {
                getManager().stopObservingDevicePresence(request)
            }.onSuccess {
                Log.d(tag, "Stopped observing presence for associationId=${association.id}")
            }.onFailure { error ->
                Log.w(tag, "Unable to stop observing presence for associationId=${association.id}", error)
            }
        }
    }

    private fun readMyAssociations(): List<AssociationInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return emptyList()
        }

        return runCatching {
            getManager().myAssociations
        }.onFailure { error ->
            Log.e(tag, "Unable to read myAssociations", error)
        }.getOrElse { emptyList() }
    }

    private fun finishPendingAssociationError(code: String, message: String, error: Throwable?) {
        pendingAssociationResult?.error(code, message, error?.stackTraceToString())
        pendingAssociationResult = null
        pendingAssociationRequest = null
        pendingAssociationDisplayName = null
        pendingAssociationMacAddress = null
        if (error != null) {
            Log.e(tag, message, error)
        }
    }

    companion object {
        private const val REQUEST_ASSOCIATE = 46026
    }
}

internal object CompanionDeviceStorage {
    private const val PREFS_NAME = "companion_device_manager"
    private const val KEY_BACKGROUND_CALLBACK_HANDLE = "background_callback_handle"
    private const val KEY_BACKGROUND_DISPATCHER_HANDLE = "background_dispatcher_handle"
    private const val KEY_LAST_EVENT_JSON = "last_event_json"

    fun storeBackgroundCallbackHandle(context: Context?, handle: Long) {
        context ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BACKGROUND_CALLBACK_HANDLE, handle)
            .commit()
    }

    fun getBackgroundCallbackHandle(context: Context?): Long? {
        context ?: return null
        val handle = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BACKGROUND_CALLBACK_HANDLE, 0L)
        return handle.takeIf { it != 0L }
    }

    fun clearBackgroundCallbackHandle(context: Context?) {
        context ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BACKGROUND_CALLBACK_HANDLE)
            .commit()
    }

    fun storeBackgroundDispatcherHandle(context: Context?, handle: Long) {
        context ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_BACKGROUND_DISPATCHER_HANDLE, handle)
            .commit()
    }

    fun getBackgroundDispatcherHandle(context: Context?): Long? {
        context ?: return null
        val handle = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_BACKGROUND_DISPATCHER_HANDLE, 0L)
        return handle.takeIf { it != 0L }
    }

    fun clearBackgroundDispatcherHandle(context: Context?) {
        context ?: return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BACKGROUND_DISPATCHER_HANDLE)
            .commit()
    }

    fun persistEvent(context: Context?, payload: Map<String, Any?>) {
        context ?: return
        val json = toJson(payload)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_EVENT_JSON, json)
            .commit()
    }

    fun getLastEventMap(context: Context?): Map<String, Any?>? {
        context ?: return null
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_EVENT_JSON, null)
            ?: return null
        return runCatching { parseJsonObject(json) }.getOrNull()
    }

    private fun toJson(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            is Number, is Boolean -> value.toString()
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, nested) ->
                "${toJson(key.toString())}:${toJson(nested)}"
            }
            is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]") { toJson(it) }
            else -> toJson(value.toString())
        }
    }

    private fun parseJsonObject(json: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return org.json.JSONObject(json).let { objectJson ->
            objectJson.keys().asSequence().associateWith { key ->
                when (val raw = objectJson.get(key)) {
                    org.json.JSONObject.NULL -> null
                    is org.json.JSONObject -> parseJsonObject(raw.toString())
                    is org.json.JSONArray -> raw.toList()
                    else -> raw
                }
            }
        }
    }

    private fun org.json.JSONArray.toList(): List<Any?> {
        return List(length()) { index ->
            when (val raw = get(index)) {
                org.json.JSONObject.NULL -> null
                is org.json.JSONObject -> parseJsonObject(raw.toString())
                is org.json.JSONArray -> raw.toList()
                else -> raw
            }
        }
    }
}
