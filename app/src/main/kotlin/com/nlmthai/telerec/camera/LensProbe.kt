package com.nlmthai.telerec.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.MediaRecorder
import android.util.Log
import android.util.Range
import android.util.Size
import com.nlmthai.telerec.core.LensCatalog
import com.nlmthai.telerec.core.LensCatalog.Access
import com.nlmthai.telerec.core.LensCatalog.Candidate
import com.nlmthai.telerec.core.LensLabel
import com.nlmthai.telerec.core.VideoPreset

private const val TAG = "TeleRec/LensProbe"

/**
 * Finds this phone's back lenses and how each can be streamed (PLAN.md, "Lens discovery").
 * The rules live in `LensCatalog` (tested in `core`); this only reads Camera2.
 */
object LensProbe {
    data class Inventory(
        /** Video lenses, widest first. The main lens is always one of them. */
        val video: List<LensCatalog.Entry>,
        /** Photo mode's zoom steps: every lens the logical camera can zoom to. */
        val photo: List<LensCatalog.Entry>,
        /** The default back camera: the main lens in video, and photo mode's camera. */
        val main: Candidate,
        /** Photo mode's CONTROL_ZOOM_RATIO range on `main`. */
        val zoomRatioRange: Range<Float>,
        /** Human-readable probe results, logged and shown in the lens info dialog. */
        val report: String,
    )

    fun probe(manager: CameraManager, supportedPhone: Boolean): Inventory {
        val back = manager.cameraIdList.filter {
            manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraMetadata.LENS_FACING_BACK
        }
        val mainId = back.firstOrNull() ?: throw CameraService.SetupException("No back camera available")
        val mainChars = manager.getCameraCharacteristics(mainId)
        val main = Candidate(mainId, null, Access.STANDALONE, fieldOfView(mainChars), presets(mainChars, mainChars))

        val others = mutableListOf<Candidate>()
        val report = StringBuilder("Back cameras: ${back.joinToString()} (main $mainId, supported phone: $supportedPhone)\n")
        for (id in back) {
            val chars = manager.getCameraCharacteristics(id)
            val physical = if (isLogical(chars)) chars.physicalCameraIds.toList() else emptyList()
            if (id != mainId && physical.isEmpty()) {
                others += Candidate(id, null, Access.STANDALONE, fieldOfView(chars), presets(chars, chars))
            }
            for (p in physical) {
                val pc = manager.getCameraCharacteristics(p)
                // Frame rates are the logical camera's: that's the device the request goes to.
                others += Candidate(id, p, Access.PHYSICAL, fieldOfView(pc), presets(pc, chars))
            }
        }
        for (c in listOf(main) + others) {
            val m = c.fieldOfView?.let { fov -> main.fieldOfView?.let { LensLabel.magnification(it, fov) } }
            report.append("  ${c.cameraId}${c.physicalId?.let { "/$it" } ?: ""} ${c.access}: ")
                .append("fov ${c.fieldOfView?.let { "%.1f°".format(it) } ?: "?"}, ")
                .append("${m?.let(LensLabel::format) ?: "?"}, presets ${c.presets.joinToString { it.rawValue }.ifEmpty { "none" }}\n")
        }

        val video = LensCatalog.build(main, others, supportedPhone)
        // Photo zooms the logical camera, so every lens counts whatever it can stream.
        val all = VideoPreset.entries.toList()
        val photo = LensCatalog.build(main.copy(presets = all), others.map { it.copy(presets = all) }, supportedPhone = true)
        val zoom = mainChars[CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE] ?: Range(1f, 1f)
        report.append("Video lenses: ${video.joinToString { it.info.label }}; photo steps: ${photo.joinToString { it.info.label }}; zoom ratio $zoom")
        Log.i(TAG, report.toString())
        return Inventory(video, photo, main, zoom, report.toString())
    }

    private fun isLogical(chars: CameraCharacteristics): Boolean =
        chars[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

    /** Horizontal field of view of the active pixel area, from focal length and sensor size. */
    fun fieldOfView(chars: CameraCharacteristics): Double? {
        val focal = chars[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return null
        val physical = chars[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
        val pixels = chars[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        val active = chars[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        val width = if (pixels != null && active != null && pixels.width > 0) {
            physical.width * active.width() / pixels.width
        } else {
            physical.width
        }
        return LensLabel.fieldOfView(focal.toDouble(), width.toDouble())
    }

    /**
     * Presets a MediaRecorder-sized stream from this lens can record: the size is
     * offered, the sensor is fast enough, and the requesting camera has the frame rate.
     */
    fun presets(stream: CameraCharacteristics, requesting: CameraCharacteristics): List<VideoPreset> {
        val map = stream[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP] ?: return emptyList()
        val sizes = map.getOutputSizes(MediaRecorder::class.java)?.toSet() ?: return emptyList()
        val fpsRanges = requesting[CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES] ?: return emptyList()
        return VideoPreset.entries.filter { p ->
            val size = Size(p.width, p.height)
            size in sizes &&
                map.getOutputMinFrameDuration(MediaRecorder::class.java, size) <= 1_000_000_000L / p.fps + 50_000 &&
                fpsRanges.any { it.upper == p.fps }
        }
    }

    /** Largest 16:9 (video) or 4:3 (photo) preview size up to 1080p. */
    fun previewSize(chars: CameraCharacteristics, aspect: Double): Size {
        val map = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
        val sizes = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        val fits = sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - aspect) < 0.02 && it.height <= 1080 }
        return fits.maxByOrNull { it.width * it.height } ?: sizes.maxByOrNull { it.width * it.height } ?: Size(1920, 1080)
    }

    /** Largest 4:3 JPEG, else the largest JPEG. */
    fun jpegSize(chars: CameraCharacteristics): Size {
        val sizes = chars[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.JPEG).orEmpty()
        val fourThree = sizes.filter { kotlin.math.abs(it.width.toDouble() / it.height - 4.0 / 3) < 0.02 }
        return (fourThree.ifEmpty { sizes.toList() }).maxByOrNull { it.width.toLong() * it.height } ?: Size(4032, 3024)
    }
}
