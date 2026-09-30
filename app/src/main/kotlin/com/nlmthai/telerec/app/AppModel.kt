package com.nlmthai.telerec.app

import android.content.Context
import android.os.Build
import android.os.Environment
import com.nlmthai.telerec.camera.CameraService
import com.nlmthai.telerec.camera.MediaStoreSaver
import com.nlmthai.telerec.core.CaptureMode
import com.nlmthai.telerec.core.CaptureSettings
import com.nlmthai.telerec.core.LensOption
import com.nlmthai.telerec.core.RecorderController
import com.nlmthai.telerec.core.SettingsStore
import com.nlmthai.telerec.core.SupportedPhones
import com.nlmthai.telerec.watch.ConnectIqService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Wires the real camera, gallery and Connect IQ into the recorder, like the iOS
 * AppModel. One per process (`TeleRecApp`), so it outlives the activity: the
 * foreground service keeps the camera open while the screen is off.
 * Main thread only.
 */
class AppModel(context: Context) {
    val supportedPhone = SupportedPhones.match(Build.MANUFACTURER, Build.MODEL)
    val phoneName: String = supportedPhone?.name ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    private val settingsStore = SettingsStore(
        SharedPrefsStore(context.getSharedPreferences("telerec", Context.MODE_PRIVATE)),
    )
    private val _settings = MutableStateFlow(settingsStore.settings)
    val settings: StateFlow<CaptureSettings> = _settings.asStateFlow()

    val camera = CameraService(context, supportedPhone != null)
    val watch = ConnectIqService(context)
    private val scope = MainScope()

    /** Recordings are written here first, then copied into the gallery. */
    private val recordingsDir: File =
        context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: File(context.filesDir, "recordings")

    val recorder = RecorderController(
        camera, MediaStoreSaver(context), watch, scope,
        makeTempFile = { File(recordingsDir, "TeleRec-${UUID.randomUUID()}.mp4") },
    )

    private val _setupError = MutableStateFlow<String?>(null)
    val setupError: StateFlow<String?> = _setupError.asStateFlow()

    /** The foreground service is running, so the camera may be open. */
    private var cameraWanted = false

    init {
        settingsStore.onChange = { _settings.value = it }
        recorder.onLensRequest = { selectLens(it) }
        recorder.onModeRequest = { switchMode(it) }
        camera.onNeedsReconfigure = { if (cameraWanted) scope.launch { applySettings() } }
    }

    /** Process start: the watch link runs as long as the process does. */
    fun launch() {
        watch.start()
    }

    /** Called by `CaptureService` once it's in the foreground. */
    fun startCamera() {
        if (cameraWanted) return
        cameraWanted = true
        // Start the session immediately so a watch press records without warm-up. After
        // the notification's Stop, the last recording may still be saving: wait for it.
        scope.launch {
            recorder.stateFlow.first { !recorder.isBusy }
            applySettings()
        }
    }

    /** The service's Stop action. A recording in progress is finished and saved. */
    fun stopCamera() {
        if (!cameraWanted) return
        cameraWanted = false
        recorder.cameraBecameUnavailable()
        camera.close()
    }

    suspend fun applySettings() {
        try {
            if (!cameraWanted || recorder.isBusy) return
            _setupError.value = camera.configure(settingsStore.settings)
        } catch (e: Exception) {
            _setupError.value = e.message ?: "Could not open the camera"
        } finally {
            // Tells the watch about a lens change, and answers its lens request.
            recorder.cameraReconfigured()
        }
    }

    fun updateSettings(transform: (CaptureSettings) -> CaptureSettings) {
        settingsStore.settings = transform(settingsStore.settings)
    }

    /** The settings sheet was closed. */
    fun settingsDismissed() {
        scope.launch { applySettings() }
    }

    /**
     * The phone's lens switch and the watch's UP/DOWN both land here. In video
     * mode the choice is saved; in photo mode it just zooms, with no reconfigure.
     */
    fun select(option: LensOption) {
        if (camera.mode == CaptureMode.PHOTO) {
            camera.setPhotoZoom(option.zoom)
            recorder.cameraReconfigured()
            return
        }
        updateSettings { it.copy(lens = option.lens, crop = option.crop) }
        scope.launch { applySettings() }
    }

    private fun selectLens(label: String) {
        val option = camera.availableLenses.value.firstOrNull { it.label == label }
        if (option == null) {
            recorder.cameraReconfigured() // still answer the watch
            return
        }
        select(option)
    }

    /** Reached only through `recorder.requestMode`, which refuses while busy. */
    private fun switchMode(mode: CaptureMode) {
        updateSettings { it.copy(mode = mode) }
        scope.launch { applySettings() }
    }
}
