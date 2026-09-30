package com.nlmthai.telerec.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

sealed interface RecorderState {
    data object Idle : RecorderState

    /** `startRecording` was issued; waiting for the recorder to confirm. */
    data object Starting : RecorderState
    data class Recording(val since: Instant) : RecorderState
    data object Saving : RecorderState

    /** Photo mode: a photo is being taken and saved. */
    data object Capturing : RecorderState
    data class Error(val message: String) : RecorderState
}

/**
 * The capture state machine. Video: idle → starting → recording → saving → idle.
 * Photo: idle → capturing → idle.
 * Owns no Camera2 or Connect IQ types so it can be tested with mocks.
 *
 * Confined to the main thread (the iOS `@MainActor`): call it, and deliver camera
 * events and watch commands, only there. `scope` runs the saves; in the app it is
 * a main-thread scope, in tests a test dispatcher.
 */
class RecorderController(
    private val camera: CameraControlling,
    private val saver: MediaSaving,
    private val watch: WatchChannel,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Instant::now,
    private val makeTempFile: () -> File = ::defaultTempFile,
) {
    companion object {
        const val UNAVAILABLE_MESSAGE = "Open TeleRec on phone"

        fun defaultTempFile(): File =
            File(System.getProperty("java.io.tmpdir"), "TeleRec-${UUID.randomUUID()}.mp4")
    }

    private val _state = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val stateFlow: StateFlow<RecorderState> = _state.asStateFlow()
    var state: RecorderState
        get() = _state.value
        private set(value) {
            val old = _state.value
            _state.value = value
            if (value != old) sendState(onlyIfChanged = true)
        }

    /** False while the camera is closed or interrupted. */
    private val _isCameraAvailable = MutableStateFlow(false)
    val isCameraAvailableFlow: StateFlow<Boolean> = _isCameraAvailable.asStateFlow()
    val isCameraAvailable: Boolean get() = _isCameraAvailable.value
    private var unavailableReason: String? = UNAVAILABLE_MESSAGE

    /**
     * Switches the camera to the lens with this label. The app wires this to the
     * same path as the phone's lens switch, then calls `cameraReconfigured()`.
     */
    var onLensRequest: ((String) -> Unit)? = null

    /** Switches the session to this mode, then calls `cameraReconfigured()`. */
    var onModeRequest: ((CaptureMode) -> Unit)? = null

    /**
     * A lens or mode change is in progress; its reply waits for `cameraReconfigured()`,
     * and nothing starts until then.
     */
    private var awaitingReconfigure = false

    /** Last watch shot accepted, so a retry of the same press isn't taken twice. */
    private var lastShotID: Int? = null
    private var capturingShotID: Int? = null

    /** Last watch shot saved; sent to the watch so it can confirm the press. */
    private var savedShotID: Int? = null
    private var lastSent: WatchReply? = null

    init {
        camera.onEvent = { handle(it) }
        watch.onCommand = { handle(it) }
    }

    val isRecording: Boolean
        get() = state is RecorderState.Starting || state is RecorderState.Recording

    /** Recording, saving a video, or taking a photo: lens, mode and settings are locked. */
    val isBusy: Boolean
        get() = when (state) {
            RecorderState.Starting, is RecorderState.Recording, RecorderState.Saving, RecorderState.Capturing -> true
            RecorderState.Idle, is RecorderState.Error -> false
        }

    // Commands

    fun handle(command: WatchCommand) {
        val before = state
        when (command) {
            WatchCommand.Toggle -> if (isRecording) stop() else start()
            WatchCommand.Start -> start()
            WatchCommand.Stop -> stop()
            WatchCommand.Status -> Unit
            // The reply comes from cameraReconfigured(), once the new lens is live.
            is WatchCommand.LensChange -> if (requestLens(command.label)) return
            is WatchCommand.ModeChange -> if (requestMode(command.mode)) return
            is WatchCommand.Shoot -> shoot(command.id)
        }
        // Every command gets a reply; state changes already sent one.
        if (state == before) sendState()
    }

    /** The phone's red button: record/stop in video mode, the shutter in photo mode. */
    fun toggle() {
        if (camera.mode == CaptureMode.PHOTO) shoot(null) else if (isRecording) stop() else start()
    }

    private fun start() {
        if (camera.mode != CaptureMode.VIDEO || isBusy || !isCameraAvailable || awaitingReconfigure) return
        val file = makeTempFile()
        state = RecorderState.Starting
        camera.startRecording(file)
    }

    private fun stop() {
        if (!isRecording) return
        state = RecorderState.Saving
        camera.stopRecording()
    }

    /** `id` is the watch press; null for the phone's own shutter button. */
    private fun shoot(id: Int?) {
        if (camera.mode != CaptureMode.PHOTO || isBusy || !isCameraAvailable || awaitingReconfigure) return
        if (id != null) {
            if (id == lastShotID) return // a retry of a shot already taken
            lastShotID = id
        }
        capturingShotID = id
        state = RecorderState.Capturing
        camera.capturePhoto()
    }

    /**
     * Returns false (and changes nothing) for the current lens, an unknown one,
     * or while busy: switching the input would cut the video.
     */
    private fun requestLens(label: String): Boolean {
        val onLensRequest = onLensRequest ?: return false
        if (!isCameraAvailable || isBusy || awaitingReconfigure ||
            label == camera.lensLabel || label !in camera.lensLabels
        ) return false
        awaitingReconfigure = true
        onLensRequest(label)
        return true
    }

    /**
     * The watch's hold-DOWN and the phone's VIDEO | PHOTO switch. Returns false
     * (and changes nothing) for the current mode, or while recording, saving or
     * taking a photo.
     */
    fun requestMode(mode: CaptureMode): Boolean {
        val onModeRequest = onModeRequest ?: return false
        if (!isCameraAvailable || isBusy || awaitingReconfigure || mode == camera.mode) return false
        awaitingReconfigure = true
        onModeRequest(mode)
        return true
    }

    /**
     * Call after every camera configure or zoom change, successful or not, so
     * the watch learns the new lens, zoom or mode (including changes made on the phone).
     */
    fun cameraReconfigured() {
        val force = awaitingReconfigure
        awaitingReconfigure = false
        sendState(onlyIfChanged = !force)
    }

    // Availability

    fun cameraBecameAvailable() {
        unavailableReason = null
        _isCameraAvailable.value = true
        sendState()
    }

    fun cameraBecameUnavailable(reason: String = UNAVAILABLE_MESSAGE) {
        unavailableReason = reason
        _isCameraAvailable.value = false
        val before = state
        stop()
        if (state == before) sendState()
    }

    // Camera events

    private fun handle(event: CameraEvent) {
        when (event) {
            // If stop arrived before the recorder confirmed, we're already saving.
            CameraEvent.RecordingStarted -> if (state == RecorderState.Starting) state = RecorderState.Recording(now())
            is CameraEvent.RecordingFinished -> finish(event.file, event.errorMessage)
            is CameraEvent.Interrupted -> cameraBecameUnavailable(event.reason)
            CameraEvent.InterruptionEnded -> cameraBecameAvailable()
            // A photo in flight still reports PhotoCaptured or PhotoFailed.
            is CameraEvent.RuntimeError -> when {
                isRecording -> stop()
                state != RecorderState.Saving && state != RecorderState.Capturing -> state = RecorderState.Error(event.message)
            }
            is CameraEvent.PhotoCaptured -> savePhoto(event.data)
            is CameraEvent.PhotoFailed -> if (state == RecorderState.Capturing) state = RecorderState.Error(event.message)
        }
    }

    private fun finish(file: File, errorMessage: String?) {
        if (errorMessage != null) {
            file.delete()
            state = RecorderState.Error(errorMessage)
            return
        }
        state = RecorderState.Saving
        scope.launch {
            try {
                saver.saveVideo(file)
                file.delete()
                state = RecorderState.Idle
            } catch (e: Exception) {
                // Keep the temp file so the footage isn't lost with the error.
                state = RecorderState.Error("Couldn't save to gallery: ${e.message}")
            }
        }
    }

    private fun savePhoto(data: ByteArray) {
        scope.launch {
            try {
                saver.savePhoto(data)
                capturingShotID?.let { savedShotID = it }
                state = RecorderState.Idle
            } catch (e: Exception) {
                state = RecorderState.Error("Couldn't save to gallery: ${e.message}")
            }
        }
    }

    // Watch replies

    val reply: WatchReply
        get() {
            val r = when (val s = state) {
                RecorderState.Idle -> WatchReply(WatchReply.State.IDLE, 0, "")
                RecorderState.Starting -> WatchReply(WatchReply.State.RECORDING, 0, "")
                is RecorderState.Recording -> WatchReply(
                    WatchReply.State.RECORDING,
                    maxOf(0L, Duration.between(s.since, now()).seconds).toInt(), "",
                )
                RecorderState.Saving, RecorderState.Capturing -> WatchReply(WatchReply.State.SAVING, 0, "")
                is RecorderState.Error -> WatchReply(WatchReply.State.ERROR, 0, "", msg = s.message)
            }
            return r.copy(lens = camera.lensLabel, lenses = camera.lensLabels, mode = camera.mode, shot = savedShotID)
        }

    /**
     * State changes that look identical on the wire (starting → recording at 0s)
     * are not re-sent; command replies always are.
     */
    private fun sendState(onlyIfChanged: Boolean = false) {
        var r = reply
        val reason = unavailableReason
        if (reason != null && (r.state == WatchReply.State.IDLE || r.state == WatchReply.State.ERROR)) {
            r = r.copy(state = WatchReply.State.ERROR, msg = reason)
        }
        if (onlyIfChanged && r == lastSent) return
        lastSent = r
        watch.send(r)
    }
}
