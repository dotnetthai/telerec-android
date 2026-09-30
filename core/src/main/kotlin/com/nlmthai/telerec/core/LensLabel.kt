package com.nlmthai.telerec.core

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.roundToLong
import kotlin.math.tan

/** Derives "0.5x"/"3x"/"5x" lens labels without per-model tables. */
object LensLabel {
    enum class Constituent { ULTRA_WIDE, WIDE, TELEPHOTO, OTHER }

    /**
     * Magnification of every constituent of a multi-camera relative to the wide
     * camera. `constituents` are ordered widest first; switch-over factor N-1 is
     * where constituent N takes over, relative to constituent 0 at 1.0.
     * (Ported from iOS, where virtual devices report these factors.)
     */
    fun magnifications(constituents: List<Constituent>, switchOverFactors: List<Double>): Map<Constituent, Double> {
        val wideIndex = constituents.indexOf(Constituent.WIDE)
        if (wideIndex < 0 || switchOverFactors.size != constituents.size - 1) return emptyMap()
        fun factor(i: Int) = if (i == 0) 1.0 else switchOverFactors[i - 1]
        val wide = factor(wideIndex)
        if (wide <= 0) return emptyMap()
        val result = mutableMapOf<Constituent, Double>()
        constituents.forEachIndexed { i, c -> if (c != Constituent.OTHER) result[c] = factor(i) / wide }
        return result
    }

    /**
     * Ratio of focal lengths from horizontal field of view (degrees).
     * Returns how much narrower `lensFOV` is than `wideFOV` (below 1 for ultra-wide).
     */
    fun magnification(wideFOV: Double, lensFOV: Double): Double? {
        if (wideFOV <= 0 || lensFOV <= 0 || lensFOV >= 180 || wideFOV >= 180) return null
        val rad = PI / 360
        return tan(wideFOV * rad) / tan(lensFOV * rad)
    }

    /**
     * Horizontal field of view (degrees) of a lens from its focal length and
     * sensor width in mm, as Camera2 reports them (`LENS_INFO_AVAILABLE_FOCAL_LENGTHS`,
     * `SENSOR_INFO_PHYSICAL_SIZE`). Uses the sensor's long side.
     */
    fun fieldOfView(focalLengthMm: Double, sensorWidthMm: Double): Double? {
        if (focalLengthMm <= 0 || sensorWidthMm <= 0) return null
        return 2 * atan(sensorWidthMm / (2 * focalLengthMm)) * 180 / PI
    }

    /**
     * 3.0 → "3x", 2.5 → "2.5x", 0.5 → "0.5x".
     * At 1x and above, rounds to the nearest 0.5; below 1x, to one decimal.
     */
    fun format(magnification: Double): String {
        val rounded = if (magnification < 0.95) roundHalfUp(magnification * 10) / 10 else roundHalfUp(magnification * 2) / 2
        return text(rounded)
    }

    /**
     * A pinched photo zoom: one decimal below 10x, whole numbers above.
     * 3.24 → "3.2x", 2.0 → "2x", 12.6 → "13x".
     */
    fun formatFree(zoom: Double): String {
        val rounded = if (zoom < 10) roundHalfUp(zoom * 10) / 10 else roundHalfUp(zoom)
        return text(rounded)
    }

    /** Swift's `rounded()` rounds halves away from zero; Kotlin's `roundToLong` rounds them up. Same for positives. */
    private fun roundHalfUp(x: Double): Double = x.roundToLong().toDouble()

    private fun text(rounded: Double): String =
        if (rounded == roundHalfUp(rounded)) "${rounded.toLong()}x" else String.format(java.util.Locale.US, "%.1fx", rounded)
}
