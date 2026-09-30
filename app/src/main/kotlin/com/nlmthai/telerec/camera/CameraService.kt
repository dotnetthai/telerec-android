package com.nlmthai.telerec.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import com.nlmthai.telerec.core.CameraControlling
import com.nlmthai.telerec.core.CameraEvent
import com.nlmthai.telerec.core.CaptureMode
import com.nlmthai.telerec.core.CaptureOrientation
import com.nlmthai.telerec.core.CaptureSettings
import com.nlmthai.telerec.core.Lens
import com.nlmthai.telerec.core.LensCatalog.Candidate
import com.nlmthai.telerec.core.LensOption
import com.nlmthai.telerec.core.VideoPreset
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "TeleRec/Camera"

/**
 * Owns the Camera2 device. Video records through one physical lens at its native
 * focal length: the lens's own camera id, or a physical stream of the logical
 * camera, never a zoomed logical camera. Photo mode uses the logical camera with
 * CONTROL_ZOOM_RATIO so pinch can zoom freely across lenses.
 *
 * Camera state is only touched on the camera thread; the StateFlows (UI state)
 * only on the main thread. Events reach `onEvent` on the main thread.
 */
class CameraService(private val context: Context, private val supportedPhone: Boolean) : CameraControlling {
    class SetupException(message: String) : Exception(message)
    private class SessionFailed : Exception("Could not configure the camera")

    override var onEvent: ((CameraEvent) -> Unit)? = null

    /** The camera came back (another app released it, the phone cooled down): configure again. Main thread. */
    var onNeedsReconfigure: (() -> Unit)? = null

    // Main-thread UI state

    private val _mode = MutableStateFlow(CaptureMode.VIDEO)
    val modeFlow: StateFlow<CaptureMode> = _mode.asStateFlow()

    /** Video mode's step (also where photo mode starts zoomed); photo mode's step when the zoom is on one. */
    private val _activeOption = MutableStateFlow<LensOption?>(null)
    val activeOption: StateFlow<LensOption?> = _activeOption.asStateFlow()

    /** Zoom steps offered in the current mode, widest first. */
    private val _availableLenses = MutableStateFlow<List<LensOption>>(emptyList())
    val availableLenses: StateFlow<List<LensOption>> = _availableLenses.asStateFlow()

    /** Photo mode's zoom, relative to the main lens. */
    private val _photoZoom = MutableStateFlow(1.0)
    val photoZoom: StateFlow<Double> = _photoZoom.asStateFlow()
    private var photoRange: ClosedFloatingPointRange<Double> = 1.0..1.0

    private val _supportedPresets = MutableStateFlow<List<VideoPreset>>(emptyList())
    val supportedPresets: StateFlow<List<VideoPreset>> = _supportedPresets.asStateFlow()

    /** Buffer size of the preview, in sensor orientation (landscape). */
    private val _previewSize = MutableStateFlow<Size?>(null)
    val previewSize: StateFlow<Size?> = _previewSize.asStateFlow()

    /** Lens probe results, for the lens info dialog. */
    private val _probeReport = MutableStateFlow("")
    val probeReport: StateFlow<String> = _probeReport.asStateFlow()

    override val mode: CaptureMode get() = _mode.value
    private fun photoLabels() = LensOption.photoLabels(_availableLenses.value, _photoZoom.value)

    /** Current zoom label ("1x", "5x", or a pinched "3.2x"); also sent to the watch. */
    override val lensLabel: String
        get() = if (mode == CaptureMode.PHOTO) photoLabels().current else _activeOption.value?.label ?: "1x"
    override val lensLabels: List<String>
        get() = if (mode == CaptureMode.PHOTO) photoLabels().all else _availableLenses.value.map { it.label }

    /**
     * The preview's SurfaceTexture lives as long as the service, not the screen: the
     * TextureView borrows it, so the session keeps running (and recording) when the
     * activity goes away. With no view attached its frames are simply dropped.
     */
    val previewTexture = SurfaceTexture(false)
    private val previewSurface = Surface(previewTexture)

    // Camera thread

    private val thread = HandlerThread("telerec.camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val cameraDispatcher = handler.asCoroutineDispatcher()
    private val executor = Executor { handler.post(it) }
    private val main = Handler(Looper.getMainLooper())
    private val mutex = Mutex()
    private val manager = context.getSystemService(CameraManager::class.java)

    private var inventory: LensProbe.Inventory? = null
    /** Physical streams that failed to configure; video falls back to the main lens. */
    private val broken = mutableSetOf<Candidate>()
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var active: Active? = null
    private var recorderSurface: Surface? = null
    private var recorderSurfacePreset: VideoPreset? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var jpegReader: ImageReader? = null
    private var photoPending = false
    /** The session is streaming; cleared when the camera is lost or closed. */
    private var live = false
    /**
     * Why the camera isn't streaming (not opened yet, closed, lost). The next
     * successful configure clears it and tells the recorder the camera is back.
     */
    private var lostReason: String? = "Not started"
    /** `configure` was called and `close` wasn't: reopen when the camera comes back. */
    private var wanted = false
    private var tooHot = false

    @Volatile private var deviceOrientation = 0

    private data class Active(
        val mode: CaptureMode,
        val target: Candidate,
        val preset: VideoPreset?,
        val audio: Boolean,
        val orientation: CaptureOrientation,
        val sensorOrientation: Int,
        val zoomRatio: Float?,
        val stabilization: Int?,
        val ois: Boolean,
        val afMode: Int,
    )

    private data class ConfigResult(
        val mode: CaptureMode,
        val option: LensOption?,
        val options: List<LensOption>,
        val presets: List<VideoPreset>,
        val photoZoom: Double,
        val photoRange: ClosedFloatingPointRange<Double>,
        val previewSize: Size,
        val warning: String?,
        val report: String,
    )

    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            deviceOrientation = (orientation + 45) / 90 * 90 % 360
        }
    }

    init {
        orientationListener.enable()
        manager.registerAvailabilityCallback(object : CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(cameraId: String) {
                // Only after losing the camera: our own reconfigures also close and reopen it.
                if (wanted && device == null && lostReason != null && !tooHot && cameraId == inventory?.main?.cameraId) {
                    main.post { onNeedsReconfigure?.invoke() }
                }
            }
        }, handler)
        context.getSystemService(PowerManager::class.java)
            .addThermalStatusListener(executor) { status -> thermalChanged(status) }
    }

    private fun emit(event: CameraEvent) = main.post { onEvent?.invoke(event) }

    // Setup

    /**
     * Configures and starts the camera for `settings`. Safe to call again after
     * settings change (must not be called while recording or taking a photo).
     * Returns a warning to show when the chosen lens couldn't be used.
     */
    suspend fun configure(settings: CaptureSettings): String? = mutex.withLock {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            throw SetupException("Camera access denied. Enable it in Settings.")
        }
        val audio = settings.audioEnabled && settings.mode == CaptureMode.VIDEO &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val result = withContext(cameraDispatcher) {
            try {
                configureOnThread(settings, audio)
            } catch (e: Exception) {
                // No session is streaming now (the old one was closed first), e.g. another
                // app has the camera: the recorder stops offering it until a configure
                // succeeds, and the availability callback retries.
                if (!live && lostReason == null) {
                    lostReason = e.message ?: "Could not open the camera"
                    emit(CameraEvent.Interrupted(lostReason!!))
                } else if (!live && e.message == IN_USE_MESSAGE) {
                    lostReason = IN_USE_MESSAGE
                    emit(CameraEvent.Interrupted(IN_USE_MESSAGE))
                }
                throw e
            }
        }
        _mode.value = result.mode
        _activeOption.value = result.option
        _availableLenses.value = result.options
        _supportedPresets.value = result.presets
        photoRange = result.photoRange
        _photoZoom.value = result.photoZoom
        _previewSize.value = result.previewSize
        _probeReport.value = result.report
        result.warning
    }

    private suspend fun configureOnThread(settings: CaptureSettings, audio: Boolean): ConfigResult {
        if (tooHot) throw SetupException(HOT_MESSAGE)
        wanted = true
        val inv = inventory ?: LensProbe.probe(manager, supportedPhone).also { inventory = it }
        val video = inv.video.filter { it.candidate !in broken }
        val lens = Lens.resolve(settings.lens, video.map { it.info.lens }) ?: throw SetupException("No back camera available")
        val entry = video.first { it.info.lens == lens }
        // No full-resolution crop steps on Android yet (PLAN.md): every step is a real lens.
        val options = LensOption.steps(video.map { it.info })
        val option = LensOption.resolve(lens, settings.crop, options)
        val presets = entry.candidate.presets.ifEmpty { inv.main.presets }
        val preset = VideoPreset.resolve(settings.preset, presets)

        if (settings.mode == CaptureMode.PHOTO) {
            val steps = LensOption.steps(inv.photo.map { it.info })
            val core = LensOption.photoZoomRange(steps)
            // The pinch range, within what the logical camera can zoom.
            val low = maxOf(core.start, inv.zoomRatioRange.lower.toDouble())
            val range = low..maxOf(low, minOf(core.endInclusive, inv.zoomRatioRange.upper.toDouble()))
            // Start where video was framed, then pinch or step from there.
            val zoom = (option?.zoom ?: 1.0).coerceIn(range)
            val size = openSession(CaptureMode.PHOTO, inv.main, null, false, settings.orientation, zoom.toFloat())
            return ConfigResult(
                CaptureMode.PHOTO, steps.firstOrNull { kotlin.math.abs(it.zoom - zoom) < 0.02 }, steps, presets,
                zoom, range, size, null, inv.report,
            )
        }

        return try {
            val size = openSession(CaptureMode.VIDEO, entry.candidate, preset, audio, settings.orientation, null)
            ConfigResult(CaptureMode.VIDEO, option, options, presets, 1.0, 1.0..1.0, size, null, inv.report)
        } catch (e: SessionFailed) {
            if (entry.candidate == inv.main) throw SetupException("Could not open the camera")
            // e.g. the telephoto streams only small sizes: record through the main lens instead.
            Log.w(TAG, "Lens ${entry.info.label} failed to configure, falling back to 1x", e)
            broken += entry.candidate
            val fallback = configureOnThread(settings, audio)
            fallback.copy(warning = "The ${entry.info.label} lens can't record video on this phone, so TeleRec uses 1x")
        }
    }

    /** Opens `target` (reusing the open device when it's the same camera) and starts the preview. */
    private suspend fun openSession(
        mode: CaptureMode, target: Candidate, preset: VideoPreset?, audio: Boolean,
        orientation: CaptureOrientation, zoom: Float?,
    ): Size {
        if (recorder != null) finishRecording()
        session?.close()
        session = null
        live = false
        var device = this.device
        if (device == null || device.id != target.cameraId) {
            device?.close()
            this.device = null
            device = openDevice(target.cameraId)
            this.device = device
        }
        val deviceChars = manager.getCameraCharacteristics(target.cameraId)
        val streamChars = manager.getCameraCharacteristics(target.physicalId ?: target.cameraId)

        val preview = LensProbe.previewSize(streamChars, if (mode == CaptureMode.PHOTO) 4.0 / 3 else 16.0 / 9)
        previewTexture.setDefaultBufferSize(preview.width, preview.height)
        val surfaces = mutableListOf(previewSurface)

        var newRecorderSurface: Surface? = null
        var newJpegReader: ImageReader? = null
        if (mode == CaptureMode.VIDEO) {
            val p = preset ?: throw SetupException("This lens can't record video")
            // A persistent surface is sized by the recorder it was prepared with.
            if (recorderSurface == null || recorderSurfacePreset != p) {
                newRecorderSurface = MediaCodec.createPersistentInputSurface()
                primeRecorderSurface(newRecorderSurface, p)
            }
            surfaces += newRecorderSurface ?: recorderSurface!!
        } else {
            val size = LensProbe.jpegSize(deviceChars)
            newJpegReader = ImageReader.newInstance(size.width, size.height, android.graphics.ImageFormat.JPEG, 2).apply {
                setOnImageAvailableListener({ reader -> photoAvailable(reader) }, handler)
            }
            surfaces += newJpegReader.surface
        }

        val stabilization = deviceChars[CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES]
            ?.takeIf { mode == CaptureMode.VIDEO && CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON in it }
            ?.let { CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON }
        val ois = (streamChars[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: IntArray(0))
            .contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        val afModes = deviceChars[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: IntArray(0)
        val af = if (mode == CaptureMode.VIDEO) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val a = Active(
            mode, target, preset, audio, orientation,
            streamChars[CameraCharacteristics.SENSOR_ORIENTATION] ?: 90,
            // Video never zooms: the main lens at 1.0, other lenses through their own stream.
            zoomRatio = if (deviceChars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] != null) zoom ?: 1f else null,
            stabilization = stabilization, ois = ois,
            afMode = if (af in afModes) af else CameraMetadata.CONTROL_AF_MODE_OFF,
        )

        val outputs = surfaces.map { s -> OutputConfiguration(s).apply { target.physicalId?.let { setPhysicalCameraId(it) } } }
        val s = try {
            createSession(device, outputs, repeatingRequest(device, a, recording = false))
        } catch (e: Exception) {
            newRecorderSurface?.release()
            newJpegReader?.close()
            throw e
        }
        session = s
        active = a
        // The new session replaced the old one, so the old outputs are free now.
        newRecorderSurface?.let {
            recorderSurface?.release()
            recorderSurface = it
            recorderSurfacePreset = preset
        }
        if (mode == CaptureMode.PHOTO) {
            jpegReader?.close()
            jpegReader = newJpegReader
        }
        s.setRepeatingRequest(repeatingRequest(device, a, recording = false), null, handler)
        live = true
        if (lostReason != null) {
            lostReason = null
            emit(CameraEvent.InterruptionEnded)
        }
        return preview
    }

    @SuppressLint("MissingPermission") // checked in configure()
    private suspend fun openDevice(id: String): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cont.resume(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                if (cont.isActive) {
                    camera.close()
                    cont.resumeWithException(SetupException(IN_USE_MESSAGE))
                } else {
                    lost(camera, IN_USE_MESSAGE, retry = false)
                }
            }

            override fun onError(camera: CameraDevice, error: Int) {
                val (message, retry) = when (error) {
                    ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> IN_USE_MESSAGE to false
                    ERROR_CAMERA_DISABLED -> "Camera disabled by a device policy" to false
                    else -> "Camera error" to true
                }
                Log.e(TAG, "Camera $id error $error")
                if (cont.isActive) {
                    camera.close()
                    cont.resumeWithException(SetupException(message))
                } else {
                    lost(camera, message, retry)
                }
            }
        }, handler)
    }

    private suspend fun createSession(
        device: CameraDevice, outputs: List<OutputConfiguration>, parameters: CaptureRequest,
    ): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
                override fun onConfigureFailed(session: CameraCaptureSession) = cont.resumeWithException(SessionFailed())
            })
        config.sessionParameters = parameters
        val supported = try {
            device.isSessionConfigurationSupported(config)
        } catch (e: UnsupportedOperationException) {
            true // the phone can't answer; try it
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!supported) {
            cont.resumeWithException(SessionFailed())
            return@suspendCancellableCoroutine
        }
        try {
            device.createCaptureSession(config)
        } catch (e: Exception) {
            cont.resumeWithException(SessionFailed().apply { initCause(e) })
        }
    }

    private fun repeatingRequest(device: CameraDevice, a: Active, recording: Boolean): CaptureRequest {
        val template = if (a.mode == CaptureMode.VIDEO) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        return device.createCaptureRequest(template).apply {
            addTarget(previewSurface)
            if (recording) recorderSurface?.let(::addTarget)
            applyCommon(this, a)
        }.build()
    }

    private fun applyCommon(b: CaptureRequest.Builder, a: Active) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AF_MODE, a.afMode)
        a.preset?.takeIf { a.mode == CaptureMode.VIDEO }?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange(a, it)) }
        a.zoomRatio?.let { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, it) }
        a.stabilization?.let { b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, it) }
        if (a.ois) b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    /** A fixed frame rate if the camera has one, else the range that tops out at it. */
    private fun fpsRange(a: Active, preset: VideoPreset): Range<Int> {
        val ranges = manager.getCameraCharacteristics(a.target.cameraId)[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES]
            .orEmpty()
        return ranges.firstOrNull { it.lower == preset.fps && it.upper == preset.fps }
            ?: ranges.filter { it.upper == preset.fps }.maxByOrNull { it.lower }
            ?: Range(preset.fps, preset.fps)
    }

    // Recording

    private val videoMime: String by lazy {
        val hevc = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, 3840, 2160).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
        }
        if (MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(hevc) != null) {
            MediaFormat.MIMETYPE_VIDEO_HEVC
        } else {
            MediaFormat.MIMETYPE_VIDEO_AVC
        }
    }

    private fun bitRate(preset: VideoPreset): Int {
        val hevc = videoMime == MediaFormat.MIMETYPE_VIDEO_HEVC
        return when (preset) {
            VideoPreset.HD1080P60 -> if (hevc) 20_000_000 else 30_000_000
            VideoPreset.UHD4K30 -> if (hevc) 40_000_000 else 60_000_000
            VideoPreset.UHD4K60 -> if (hevc) 60_000_000 else 90_000_000
        }
    }

    private fun newRecorder(
        preset: VideoPreset, audio: Boolean, file: File, surface: Surface, rotation: Int, maxBytes: Long,
    ) = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()).apply {
        if (audio) setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        setVideoSource(MediaRecorder.VideoSource.SURFACE)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setOutputFile(file)
        setVideoEncodingBitRate(bitRate(preset))
        setVideoFrameRate(preset.fps)
        setVideoSize(preset.width, preset.height)
        setVideoEncoder(if (videoMime == MediaFormat.MIMETYPE_VIDEO_HEVC) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
        if (audio) {
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(48_000)
            setAudioEncodingBitRate(192_000)
            setAudioChannels(2)
        }
        setOrientationHint(rotation)
        setInputSurface(surface)
        if (maxBytes > 0) setMaxFileSize(maxBytes)
    }

    /** Camera2Video's trick: a prepared-then-released recorder gives the persistent surface its buffer size. */
    private fun primeRecorderSurface(surface: Surface, preset: VideoPreset) {
        val file = File(context.cacheDir, "prime.mp4")
        val r = newRecorder(preset, false, file, surface, 0, 0)
        try {
            r.prepare()
        } finally {
            r.release()
            file.delete()
        }
    }

    override fun startRecording(file: File) {
        handler.post {
            if (recorder != null) return@post
            val s = session
            val a = active
            val device = device
            val surface = recorderSurface
            if (s == null || a == null || device == null || surface == null || a.mode != CaptureMode.VIDEO || a.preset == null) {
                emit(CameraEvent.RecordingFinished(file, "Camera not ready"))
                return@post
            }
            var r: MediaRecorder? = null
            try {
                file.parentFile?.mkdirs()
                // The gallery copy needs as much space again, plus a margin.
                val free = StatFs(file.parentFile?.path ?: context.filesDir.path).availableBytes
                val budget = free / 2 - 500L * 1024 * 1024
                if (budget < 1024L * 1024 * 1024) throw IOException("Not enough storage to record")
                r = newRecorder(a.preset, a.audio, file, surface, rotation(a), budget).apply {
                    setOnInfoListener { _, what, _ ->
                        if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                            what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                        ) handler.post { finishRecording() }
                    }
                    setOnErrorListener { _, what, extra ->
                        Log.e(TAG, "MediaRecorder error $what/$extra")
                        handler.post { finishRecording("Recording failed") }
                    }
                    prepare()
                }
                s.setRepeatingRequest(repeatingRequest(device, a, recording = true), null, handler)
                r.start()
                recorder = r
                recordingFile = file
                emit(CameraEvent.RecordingStarted)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't start recording", e)
                r?.release()
                runCatching { s.setRepeatingRequest(repeatingRequest(device, a, recording = false), null, handler) }
                emit(CameraEvent.RecordingFinished(file, e.message ?: "Couldn't start recording"))
            }
        }
    }

    override fun stopRecording() {
        handler.post { finishRecording() }
    }

    /** Camera thread. Stops the recorder if one is running and reports the file. */
    private fun finishRecording(error: String? = null) {
        val r = recorder ?: return
        val file = recordingFile ?: return
        recorder = null
        recordingFile = null
        var message = error
        try {
            r.stop()
        } catch (e: RuntimeException) {
            // No frames reached the file: it's unusable.
            if (message == null) message = "Recording was too short to save"
        }
        r.release()
        val s = session
        val a = active
        val device = device
        if (s != null && a != null && device != null) {
            runCatching { s.setRepeatingRequest(repeatingRequest(device, a, recording = false), null, handler) }
        }
        emit(CameraEvent.RecordingFinished(file, message))
    }

    /** Rotation for the file: which way up the phone is, restricted to portrait or landscape by the setting. */
    private fun rotation(a: Active): Int {
        val d = deviceOrientation
        val upright = when (a.orientation) {
            CaptureOrientation.PORTRAIT -> if (d == 180) 180 else 0
            CaptureOrientation.LANDSCAPE -> if (d == 270) 270 else 90
        }
        return (a.sensorOrientation + upright) % 360
    }

    // Photo

    override fun capturePhoto() {
        handler.post {
            val s = session
            val a = active
            val reader = jpegReader
            val device = device
            if (s == null || a == null || reader == null || device == null || a.mode != CaptureMode.PHOTO || photoPending) {
                emit(CameraEvent.PhotoFailed("Camera not ready"))
                return@post
            }
            try {
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(previewSurface)
                    addTarget(reader.surface)
                    applyCommon(this, a)
                    set(CaptureRequest.JPEG_ORIENTATION, rotation(a))
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                }.build()
                photoPending = true
                s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                        if (!photoPending) return
                        photoPending = false
                        emit(CameraEvent.PhotoFailed("Couldn't take the photo"))
                    }
                }, handler)
            } catch (e: Exception) {
                photoPending = false
                emit(CameraEvent.PhotoFailed(e.message ?: "Couldn't take the photo"))
            }
        }
    }

    private fun photoAvailable(reader: ImageReader) {
        val image = reader.acquireNextImage() ?: return
        val bytes = try {
            val buffer = image.planes[0].buffer
            ByteArray(buffer.remaining()).also { buffer.get(it) }
        } finally {
            image.close()
        }
        if (!photoPending) return
        photoPending = false
        emit(CameraEvent.PhotoCaptured(bytes))
    }

    /** Photo mode only: any zoom in the pinch range, optical or digital. Main thread. */
    fun setPhotoZoom(zoom: Double) {
        if (mode != CaptureMode.PHOTO || _availableLenses.value.isEmpty()) return
        val clamped = zoom.coerceIn(photoRange)
        _photoZoom.value = clamped
        _activeOption.value = _availableLenses.value.firstOrNull { kotlin.math.abs(it.zoom - clamped) < 0.02 }
        handler.post {
            val a = active?.takeIf { it.mode == CaptureMode.PHOTO } ?: return@post
            val updated = a.copy(zoomRatio = a.zoomRatio?.let { clamped.toFloat() })
            active = updated
            val device = device ?: return@post
            runCatching { session?.setRepeatingRequest(repeatingRequest(device, updated, recording = false), null, handler) }
        }
    }

    // Losing the camera

    /** Closes the camera (the service stopped). A recording in progress is finished first. */
    fun close() {
        handler.post {
            wanted = false
            releaseDevice()
            lostReason = "Closed"
        }
    }

    private fun releaseDevice() {
        finishRecording()
        if (photoPending) {
            photoPending = false
            emit(CameraEvent.PhotoFailed("Camera closed"))
        }
        session?.close()
        session = null
        device?.close()
        device = null
        active = null
        live = false
    }

    /** Camera thread: the device went away under us. */
    private fun lost(camera: CameraDevice, reason: String, retry: Boolean) {
        if (camera != device) {
            camera.close()
            return
        }
        Log.w(TAG, "Camera lost: $reason")
        emit(CameraEvent.Interrupted(reason))
        releaseDevice()
        lostReason = reason
        if (retry && wanted) handler.postDelayed({ if (wanted && device == null) main.post { onNeedsReconfigure?.invoke() } }, 1000)
    }

    private fun thermalChanged(status: Int) {
        val hot = status >= PowerManager.THERMAL_STATUS_SEVERE
        if (hot == tooHot) return
        tooHot = hot
        if (hot) {
            Log.w(TAG, "Thermal status $status: closing the camera")
            if (device != null) {
                emit(CameraEvent.Interrupted(HOT_MESSAGE))
                releaseDevice()
                lostReason = HOT_MESSAGE
            }
        } else if (wanted && device == null) {
            main.post { onNeedsReconfigure?.invoke() }
        }
    }

    companion object {
        const val IN_USE_MESSAGE = "Camera in use by another app"
        const val HOT_MESSAGE = "Camera stopped: phone too hot"
    }
}
