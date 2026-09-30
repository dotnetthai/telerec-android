package com.nlmthai.telerec.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.nlmthai.telerec.BuildConfig
import com.nlmthai.telerec.core.OneInFlight
import com.nlmthai.telerec.core.TeleRecIDs
import com.nlmthai.telerec.core.WatchChannel
import com.nlmthai.telerec.core.WatchCommand
import com.nlmthai.telerec.core.WatchReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "TeleRec/Watch"

/**
 * Bridges the Connect IQ Mobile SDK to `WatchChannel`. Unlike iOS there's no
 * device-selection round trip: the SDK lists the watches paired in Garmin Connect.
 * Main thread only.
 */
class ConnectIqService(private val context: Context) : WatchChannel {
    enum class Status(val title: String) {
        STARTING("Connecting to Garmin Connect"),
        NO_GARMIN_CONNECT("Garmin Connect not installed"),
        NO_DEVICE("No watch paired in Garmin Connect"),
        NOT_CONNECTED("Watch not connected"),
        APP_NOT_INSTALLED("No app on watch"),
        CONNECTED("Watch connected"),
    }

    private val _status = MutableStateFlow(Status.STARTING)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    override var onCommand: ((WatchCommand) -> Unit)? = null

    private inner class Peer(val device: IQDevice) {
        val app = IQApp(TeleRecIDs.connectIQAppId)
        var status: IQDevice.IQDeviceStatus = IQDevice.IQDeviceStatus.UNKNOWN
        /** null until the watch answers. */
        var appInstalled: Boolean? = null
        val sender = OneInFlight<WatchReply> { transmit(this, it) }
    }

    private val main = Handler(Looper.getMainLooper())
    /** TETHERED talks to the Connect IQ simulator over adb (see README); a debug-build switch. */
    private val ciq: ConnectIQ = ConnectIQ.getInstance(
        context,
        if (BuildConfig.CIQ_TETHERED) ConnectIQ.IQConnectType.TETHERED else ConnectIQ.IQConnectType.WIRELESS,
    )
    private var ready = false
    private val peers = mutableMapOf<Long, Peer>()
    private var lastReply: WatchReply? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /** Call once, at process start. */
    fun start() {
        ciq.initialize(context, true, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() = onMain {
                ready = true
                refreshDevices()
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) = onMain {
                Log.e(TAG, "Connect IQ init failed: $status")
                ready = false
                _status.value = when (status) {
                    ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED, ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> Status.NO_GARMIN_CONNECT
                    else -> Status.NOT_CONNECTED
                }
            }

            override fun onSdkShutDown() = onMain {
                ready = false
                peers.clear()
                refreshStatus()
            }
        })
    }

    fun shutdown() {
        if (!ready) return
        runCatching { ciq.unregisterAllForEvents() }
        runCatching { ciq.shutdown(context) }
        ready = false
    }

    /** Re-reads the paired watches (e.g. one was paired in Garmin Connect since launch). */
    fun refreshDevices() {
        if (!ready) return
        val devices = try {
            ciq.knownDevices.orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't list devices", e)
            emptyList()
        }
        runCatching { ciq.unregisterAllForEvents() }
        peers.clear()
        for (device in devices) {
            val peer = Peer(device)
            peers[device.deviceIdentifier] = peer
            try {
                ciq.registerForDeviceEvents(device) { d, status -> onMain { deviceStatusChanged(d, status) } }
                ciq.registerForAppEvents(device, peer.app) { d, _, message, status ->
                    onMain { received(d, message, status) }
                }
                deviceStatusChanged(device, ciq.getDeviceStatus(device))
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't register for ${device.friendlyName}", e)
            }
        }
        refreshStatus()
    }

    private fun deviceStatusChanged(device: IQDevice, status: IQDevice.IQDeviceStatus) {
        val peer = peers[device.deviceIdentifier] ?: return
        peer.status = status
        if (status == IQDevice.IQDeviceStatus.CONNECTED) checkApp(peer)
        refreshStatus()
    }

    private fun checkApp(peer: Peer) {
        try {
            ciq.getApplicationInfo(TeleRecIDs.connectIQAppId, peer.device, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) = onMain {
                    if (peers[peer.device.deviceIdentifier] !== peer) return@onMain
                    peer.appInstalled = true
                    refreshStatus()
                    lastReply?.let { peer.sender.send(it) }
                }

                override fun onApplicationNotInstalled(applicationId: String) = onMain {
                    if (peers[peer.device.deviceIdentifier] !== peer) return@onMain
                    peer.appInstalled = false
                    refreshStatus()
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't check the watch app", e)
        }
    }

    private fun refreshStatus() {
        val all = peers.values.toList()
        val connected = all.filter { it.status == IQDevice.IQDeviceStatus.CONNECTED }
        _deviceName.value = (connected.firstOrNull() ?: all.firstOrNull())?.device?.friendlyName
        if (!ready && _status.value == Status.NO_GARMIN_CONNECT) return
        _status.value = when {
            !ready -> Status.STARTING
            all.isEmpty() -> Status.NO_DEVICE
            connected.any { it.appInstalled != false } -> Status.CONNECTED
            connected.isNotEmpty() -> Status.APP_NOT_INSTALLED
            else -> Status.NOT_CONNECTED
        }
    }

    private fun received(device: IQDevice, message: List<Any?>?, status: ConnectIQ.IQMessageStatus) {
        val peer = peers[device.deviceIdentifier]
        // A message proves the app is installed and the watch is reachable.
        if (peer != null && peer.appInstalled != true) {
            peer.appInstalled = true
            refreshStatus()
        }
        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
            Log.e(TAG, "Receive failed: $status")
            return
        }
        val cmd = WatchCommand.parse(message)
        if (cmd == null) {
            Log.e(TAG, "Ignoring unknown message: $message")
            return
        }
        onCommand?.invoke(cmd)
    }

    // Sending

    override fun send(reply: WatchReply) {
        lastReply = reply
        for (peer in peers.values) {
            if (peer.status == IQDevice.IQDeviceStatus.CONNECTED && peer.appInstalled != false) peer.sender.send(reply)
        }
    }

    private fun transmit(peer: Peer, reply: WatchReply) {
        try {
            ciq.sendMessage(peer.device, peer.app, reply.message) { _, _, status ->
                onMain {
                    if (status != ConnectIQ.IQMessageStatus.SUCCESS) Log.e(TAG, "Send failed: $status")
                    peer.sender.completed()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            // Let the next reply through rather than wedging this watch.
            main.post { peer.sender.completed() }
        }
    }
}
