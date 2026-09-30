package com.nlmthai.telerec.core

import kotlin.math.abs
import kotlin.math.max

enum class VideoPreset(val rawValue: String, val width: Int, val height: Int, val fps: Int, val title: String) {
    HD1080P60("hd1080p60", 1920, 1080, 60, "1080p · 60 fps"),
    UHD4K30("uhd4k30", 3840, 2160, 30, "4K · 30 fps"),
    UHD4K60("uhd4k60", 3840, 2160, 60, "4K · 60 fps");

    companion object {
        val DEFAULT = UHD4K30

        fun fromRaw(raw: String?): VideoPreset? = entries.firstOrNull { it.rawValue == raw }

        /** The requested preset if the lens supports it, else the default, else any supported one. */
        fun resolve(requested: VideoPreset, supported: List<VideoPreset>): VideoPreset? = when {
            requested in supported -> requested
            DEFAULT in supported -> DEFAULT
            else -> supported.firstOrNull()
        }
    }
}

/**
 * Which physical back camera records. Never a zoomed logical multi-camera.
 * Declared widest first, the order the lens switch shows them.
 */
enum class Lens(val rawValue: String) {
    ULTRA_WIDE("ultraWide"), WIDE("wide"), TELEPHOTO("telephoto");

    companion object {
        fun fromRaw(raw: String?): Lens? = entries.firstOrNull { it.rawValue == raw }

        /** The requested lens if this phone has it, else the wide camera. */
        fun resolve(requested: Lens, available: List<Lens>): Lens? = when {
            requested in available -> requested
            WIDE in available -> WIDE
            else -> available.firstOrNull()
        }
    }
}

/**
 * Video records through one physical lens at full quality. Photo uses the
 * logical multi-camera so it can pinch-zoom freely, optical or digital.
 */
enum class CaptureMode(val rawValue: String) {
    VIDEO("video"), PHOTO("photo");

    val title: String get() = rawValue.uppercase()

    companion object {
        fun fromRaw(raw: String?): CaptureMode? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * One zoom step: a physical lens at its native focal length (`crop` 1), or a
 * full-resolution sensor crop of it. Android offers no crop steps yet (PLAN.md,
 * "Full-quality crop steps"), but the rules are kept so the port matches iOS.
 */
data class LensOption(
    val lens: Lens,
    val crop: Double = 1.0,
    val label: String,
    /** Magnification relative to the wide camera (0.5, 1, 2, 5 …). */
    val zoom: Double = 1.0,
) {
    val id: String get() = "${lens.rawValue}@$crop"

    /** What the phone knows about one of its lenses. */
    data class LensInfo(
        val lens: Lens,
        /** Relative to the wide camera; null if the phone didn't say. */
        val magnification: Double?,
        val label: String,
        /** Full-resolution crop factors (> 1) for the format in use. */
        val crops: List<Double> = emptyList(),
    )

    /** Photo mode's labels for the watch. */
    data class PhotoLabels(val current: String, val all: List<String>)

    companion object {
        private val defaultMagnification = mapOf(Lens.ULTRA_WIDE to 0.5, Lens.WIDE to 1.0, Lens.TELEPHOTO to 3.0)

        /**
         * Every step, widest first. A crop is left out when it lands on the same
         * label as a real lens, or when the lens's magnification is unknown and a
         * label can't be worked out.
         */
        fun steps(lenses: List<LensInfo>): List<LensOption> {
            fun value(info: LensInfo, crop: Double): Double =
                (info.magnification ?: defaultMagnification.getValue(info.lens)) * crop

            val steps = lenses.map { LensOption(lens = it.lens, label = it.label, zoom = value(it, 1.0)) }.toMutableList()
            val labels = steps.map { it.label }.toMutableSet()
            for (info in lenses) {
                val m = info.magnification ?: continue
                for (crop in info.crops.sorted()) {
                    if (crop <= 1) continue
                    val label = LensLabel.format(m * crop)
                    if (!labels.add(label)) continue
                    steps.add(LensOption(lens = info.lens, crop = crop, label = label, zoom = value(info, crop)))
                }
            }
            return steps.sortedBy { it.zoom }
        }

        /**
         * Photo mode's pinch range: the widest step up to 5x the longest real lens
         * (25x with a 5x telephoto, 5x on a phone without one).
         */
        fun photoZoomRange(steps: List<LensOption>): ClosedFloatingPointRange<Double> {
            val low = steps.firstOrNull()?.zoom ?: 1.0
            val longest = steps.filter { it.crop == 1.0 }.maxOfOrNull { it.zoom } ?: 1.0
            return low..max(low, 5 * longest)
        }

        /**
         * Photo mode's labels for the watch: the steps, plus the pinched zoom
         * ("3.2x") in its place when it isn't on a step, so UP/DOWN go to the
         * neighbouring steps.
         */
        fun photoLabels(steps: List<LensOption>, zoom: Double): PhotoLabels {
            val all = steps.map { it.label }
            steps.firstOrNull { abs(it.zoom - zoom) < 0.02 }?.let { return PhotoLabels(it.label, all) }
            val label = LensLabel.formatFree(zoom)
            if (label in all) return PhotoLabels(label, all)
            val index = steps.indexOfFirst { it.zoom > zoom }.let { if (it < 0) steps.size else it }
            val labels = all.toMutableList()
            labels.add(index, label)
            return PhotoLabels(label, labels)
        }

        /**
         * The saved step if it's still offered, else the same lens uncropped,
         * else the first step.
         */
        fun resolve(lens: Lens, crop: Double, options: List<LensOption>): LensOption? =
            options.firstOrNull { it.lens == lens && abs(it.crop - crop) < 0.01 }
                ?: options.firstOrNull { it.lens == lens && it.crop == 1.0 }
                ?: options.firstOrNull()
    }
}

enum class CaptureOrientation(val rawValue: String) {
    PORTRAIT("portrait"), LANDSCAPE("landscape");

    val title: String get() = rawValue.replaceFirstChar { it.uppercase() }

    companion object {
        fun fromRaw(raw: String?): CaptureOrientation? = entries.firstOrNull { it.rawValue == raw }
    }
}

data class CaptureSettings(
    /** Not saved: TeleRec always opens in video mode. */
    val mode: CaptureMode = CaptureMode.VIDEO,
    val preset: VideoPreset = VideoPreset.DEFAULT,
    val lens: Lens = Lens.TELEPHOTO,
    /** Full-resolution crop of `lens`; 1 is the lens itself. */
    val crop: Double = 1.0,
    val audioEnabled: Boolean = true,
    val orientation: CaptureOrientation = CaptureOrientation.PORTRAIT,
)

/** Key-value storage behind `SettingsStore`: SharedPreferences in the app, a map in tests. */
interface KeyValueStore {
    fun getString(key: String): String?
    fun getBoolean(key: String): Boolean?
    fun getDouble(key: String): Double?
    fun putString(key: String, value: String)
    fun putBoolean(key: String, value: Boolean)
    fun putDouble(key: String, value: Double)
}

/** Persists `CaptureSettings`, like the iOS `SettingsStore` over UserDefaults. */
class SettingsStore(private val store: KeyValueStore) {
    private object Key {
        const val PRESET = "settings.preset"
        const val AUDIO = "settings.audio"
        const val ORIENTATION = "settings.orientation"
        const val LENS = "settings.lens"
        const val CROP = "settings.crop"
    }

    var settings: CaptureSettings = load()
        set(value) {
            field = value
            save()
            onChange?.invoke(value)
        }

    var onChange: ((CaptureSettings) -> Unit)? = null

    private fun load(): CaptureSettings {
        var s = CaptureSettings()
        VideoPreset.fromRaw(store.getString(Key.PRESET))?.let { s = s.copy(preset = it) }
        store.getBoolean(Key.AUDIO)?.let { s = s.copy(audioEnabled = it) }
        CaptureOrientation.fromRaw(store.getString(Key.ORIENTATION))?.let { s = s.copy(orientation = it) }
        Lens.fromRaw(store.getString(Key.LENS))?.let { s = s.copy(lens = it) }
        store.getDouble(Key.CROP)?.let { s = s.copy(crop = max(1.0, it)) }
        return s
    }

    private fun save() {
        store.putString(Key.PRESET, settings.preset.rawValue)
        store.putBoolean(Key.AUDIO, settings.audioEnabled)
        store.putString(Key.ORIENTATION, settings.orientation.rawValue)
        store.putString(Key.LENS, settings.lens.rawValue)
        store.putDouble(Key.CROP, settings.crop)
    }
}
