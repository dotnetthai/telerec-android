package com.nlmthai.telerec.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.nlmthai.telerec.R
import com.nlmthai.telerec.core.RecorderState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the camera open for the watch while the screen is off or another app is
 * in front. Android only lets a camera foreground service start while TeleRec is
 * on screen, so `MainActivity` starts it; from then on the watch can record with
 * the phone locked until the notification's Stop is tapped.
 */
class CaptureService : LifecycleService() {
    private val model get() = (application as TeleRecApp).model
    private var stopping = false
    /** Waits for the last save after Stop; cancelled if TeleRec is opened again meanwhile. */
    private var stopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        lifecycleScope.launch {
            model.recorder.stateFlow.combine(model.watch.status) { state, _ -> state }.collect { state ->
                if (!stopping) nm.notify(NOTIFICATION_ID, notification(state))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        stopping = false
        stopJob?.cancel()
        stopJob = null
        // The microphone type needs the permission already granted; without it, video has no sound.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        try {
            startForeground(NOTIFICATION_ID, notification(model.recorder.state), types)
        } catch (e: Exception) {
            // Not allowed from the background (e.g. a restart while the app is off screen).
            stopSelf()
            return START_NOT_STICKY
        }
        model.startCamera()
        return START_NOT_STICKY
    }

    private fun stop() {
        stopping = true
        model.stopCamera()
        // Stay in the foreground until the last recording is in the gallery.
        stopJob?.cancel()
        stopJob = lifecycleScope.launch {
            model.recorder.stateFlow.first {
                it != RecorderState.Saving && it != RecorderState.Starting && it !is RecorderState.Recording &&
                    it != RecorderState.Capturing
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        model.stopCamera()
        super.onDestroy()
    }

    private fun notification(state: RecorderState): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when (state) {
            RecorderState.Starting, is RecorderState.Recording -> getString(R.string.notification_recording)
            RecorderState.Saving, RecorderState.Capturing -> getString(R.string.notification_saving)
            is RecorderState.Error -> state.message
            RecorderState.Idle -> "${getString(R.string.notification_ready)} · ${model.watch.status.value.title}"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.notification_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "capture"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.nlmthai.telerec.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, CaptureService::class.java))
        }
    }
}
