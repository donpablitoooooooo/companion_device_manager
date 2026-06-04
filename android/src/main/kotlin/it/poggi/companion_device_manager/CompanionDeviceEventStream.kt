package it.poggi.companion_device_manager

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

internal object CompanionDeviceEventStream {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sinkOwner: Any? = null
    private var eventSink: EventChannel.EventSink? = null

    fun attachSink(owner: Any, sink: EventChannel.EventSink) {
        mainHandler.post {
            sinkOwner = owner
            eventSink = sink
        }
    }

    fun detachSink(owner: Any) {
        mainHandler.post {
            if (sinkOwner != owner) {
                return@post
            }
            sinkOwner = null
            eventSink = null
        }
    }

    fun emit(payload: Map<String, Any?>) {
        mainHandler.post {
            eventSink?.success(payload)
        }
    }
}

