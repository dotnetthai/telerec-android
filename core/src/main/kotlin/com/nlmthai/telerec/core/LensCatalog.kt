package com.nlmthai.telerec.core

/**
 * The flagship phones TeleRec supports with more than the main lens in video.
 * Other phones still work, but video records through the main lens only
 * (photo mode zooms freely everywhere). Matching is on `Build.MODEL`.
 */
object SupportedPhones {
    data class Phone(val name: String, val modelPrefixes: List<String>)

    val all = listOf(
        Phone("Pixel 8 Pro", listOf("Pixel 8 Pro")),
        Phone("Pixel 9 Pro", listOf("Pixel 9 Pro")),
        Phone("Pixel 9 Pro XL", listOf("Pixel 9 Pro XL")),
        Phone("Pixel 10 Pro", listOf("Pixel 10 Pro")),
        Phone("Pixel 10 Pro XL", listOf("Pixel 10 Pro XL")),
        // Samsung model codes carry a region suffix: SM-S918B, SM-S918U1, SM-S9180 …
        Phone("Galaxy S23 Ultra", listOf("SM-S918")),
        Phone("Galaxy S24 Ultra", listOf("SM-S928")),
        Phone("Galaxy S25 Ultra", listOf("SM-S938")),
    )

    /** The supported phone this is, or null. */
    fun match(manufacturer: String, model: String): Phone? {
        val m = model.trim()
        return when (manufacturer.trim().lowercase()) {
            // Pixel names must match exactly: "Pixel 9 Pro" isn't a prefix match for "Pixel 9 Pro Fold".
            "google" -> all.firstOrNull { p -> p.modelPrefixes.any { it.equals(m, ignoreCase = true) } }
            "samsung" -> all.firstOrNull { p -> p.modelPrefixes.any { m.startsWith(it, ignoreCase = true) } }
            else -> null
        }
    }
}

/**
 * Turns what Camera2 reports about the back cameras into TeleRec's lenses, so the
 * rules can be tested without a phone. The app's `LensProbe` fills in the candidates.
 */
object LensCatalog {
    /** How the app can stream from a lens, best first (PLAN.md, "Video mode"). */
    enum class Access {
        /** The lens has its own camera id: open it directly. */
        STANDALONE,

        /** A physical camera of a logical multi-camera, streamed with `setPhysicalCameraId`. */
        PHYSICAL,
    }

    data class Candidate(
        /** The camera id to open. */
        val cameraId: String,
        /** Set when streaming a physical camera inside `cameraId`. */
        val physicalId: String?,
        val access: Access,
        /** Horizontal field of view in degrees; null if the phone didn't report focal length or sensor size. */
        val fieldOfView: Double?,
        /** Presets a video-sized stream from this lens supports. */
        val presets: List<VideoPreset>,
    )

    data class Entry(val info: LensOption.LensInfo, val candidate: Candidate)

    /** Below this magnification a lens is the ultra-wide; above the upper one, a telephoto. */
    private const val ULTRA_WIDE_BELOW = 0.8
    private const val TELEPHOTO_ABOVE = 1.3

    /**
     * `main` is the lens that zoom ratio 1.0 of the default back camera shows (its
     * reported focal length). Returns at most one entry per `Lens`, widest first:
     * - the main lens is always offered;
     * - on an unsupported phone, only the main lens;
     * - a lens needs a known field of view and at least one preset;
     * - a standalone camera id beats a physical stream of the same lens;
     * - of several telephotos, the narrowest (the longest reach), as on iOS.
     */
    fun build(main: Candidate, others: List<Candidate>, supportedPhone: Boolean): List<Entry> {
        val mainEntry = Entry(LensOption.LensInfo(Lens.WIDE, 1.0, "1x"), main)
        if (!supportedPhone) return listOf(mainEntry)
        val wideFOV = main.fieldOfView ?: return listOf(mainEntry)

        val byLens = mutableMapOf<Lens, Pair<Double, Candidate>>()
        for (c in others) {
            if (c.presets.isEmpty()) continue
            val m = c.fieldOfView?.let { LensLabel.magnification(wideFOV, it) } ?: continue
            val lens = when {
                m < ULTRA_WIDE_BELOW -> Lens.ULTRA_WIDE
                m > TELEPHOTO_ABOVE -> Lens.TELEPHOTO
                else -> continue // another view of the main lens
            }
            val current = byLens[lens]
            if (current == null || better(lens, m to c, current)) byLens[lens] = m to c
        }

        return Lens.entries.mapNotNull { lens ->
            if (lens == Lens.WIDE) return@mapNotNull mainEntry
            val (m, c) = byLens[lens] ?: return@mapNotNull null
            Entry(LensOption.LensInfo(lens, m, LensLabel.format(m)), c)
        }
    }

    private fun better(lens: Lens, new: Pair<Double, Candidate>, old: Pair<Double, Candidate>): Boolean {
        val (nm, nc) = new
        val (om, oc) = old
        // Same lens seen twice (standalone id and physical id): prefer opening it directly.
        if (sameLens(nm, om)) return nc.access.ordinal < oc.access.ordinal
        return if (lens == Lens.TELEPHOTO) nm > om else nm < om
    }

    private fun sameLens(a: Double, b: Double) = LensLabel.format(a) == LensLabel.format(b)
}

/**
 * One message in flight per watch; a newer reply replaces a queued one. The
 * Connect IQ adapter keeps one of these per device.
 */
class OneInFlight<M>(private val transmit: (M) -> Unit) {
    var sending = false
        private set
    var pending: M? = null
        private set

    fun send(message: M) {
        if (sending) {
            pending = message
            return
        }
        sending = true
        transmit(message)
    }

    /** Call when the transmit finished, successfully or not. */
    fun completed() {
        sending = false
        val next = pending ?: return
        pending = null
        send(next)
    }
}
