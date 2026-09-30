package com.nlmthai.telerec.core

import com.nlmthai.telerec.core.LensLabel.Constituent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensLabelTests {
    private fun labels(constituents: List<Constituent>, factors: List<Double>): List<String> {
        val m = LensLabel.magnifications(constituents, factors)
        return constituents.mapNotNull { m[it] }.map(LensLabel::format)
    }

    @Test
    fun testProTripleCameras() {
        // Switch-over factors are relative to the ultra-wide at 1.0.
        val triple = listOf(Constituent.ULTRA_WIDE, Constituent.WIDE, Constituent.TELEPHOTO)
        assertEquals(listOf("0.5x", "1x", "3x"), labels(triple, listOf(2.0, 6.0)))
        assertEquals(listOf("0.5x", "1x", "5x"), labels(triple, listOf(2.0, 10.0)))
        assertEquals(listOf("0.5x", "1x", "4x"), labels(triple, listOf(2.0, 8.0)))
    }

    @Test
    fun testDualCameras() {
        assertEquals(listOf("0.5x", "1x"), labels(listOf(Constituent.ULTRA_WIDE, Constituent.WIDE), listOf(2.0)))
        assertEquals(listOf("1x", "2x"), labels(listOf(Constituent.WIDE, Constituent.TELEPHOTO), listOf(2.0)))
    }

    @Test
    fun testUnusableData() {
        assertTrue(LensLabel.magnifications(listOf(Constituent.ULTRA_WIDE, Constituent.TELEPHOTO), listOf(3.0)).isEmpty())
        assertTrue(
            LensLabel.magnifications(listOf(Constituent.ULTRA_WIDE, Constituent.WIDE, Constituent.TELEPHOTO), emptyList()).isEmpty(),
        )
    }

    @Test
    fun testFieldOfViewFallback() {
        // ~69.4° wide vs ~25.3° tele ≈ 3.1 → 3x; ~106° ultra-wide ≈ 0.52 → 0.5x
        assertEquals("3x", LensLabel.format(LensLabel.magnification(69.4, 25.3)!!))
        assertEquals("0.5x", LensLabel.format(LensLabel.magnification(69.4, 106.0)!!))
        assertEquals("2.5x", LensLabel.format(2.5))
        assertEquals("1x", LensLabel.format(1.02))
    }

    @Test
    fun testFieldOfViewFromFocalLengthAndSensorSize() {
        // Pixel 8 Pro-like numbers: main 6.9 mm on a 9.8 mm-wide sensor, 5x tele 18 mm on 5.1 mm.
        val main = LensLabel.fieldOfView(6.9, 9.8)!!
        val tele = LensLabel.fieldOfView(18.0, 5.1)!!
        assertEquals("5x", LensLabel.format(LensLabel.magnification(main, tele)!!))
        assertNull(LensLabel.fieldOfView(0.0, 5.0))
        assertNull(LensLabel.fieldOfView(5.0, 0.0))
    }
}

class ZoomStepTests {
    private fun info(lens: Lens, m: Double?, label: String, crops: List<Double> = emptyList()) =
        LensOption.LensInfo(lens, m, label, crops)

    private fun labels(infos: List<LensOption.LensInfo>) = LensOption.steps(infos).map { it.label }

    @Test
    fun testCropsOfFortyEightMegapixelSensors() {
        assertEquals(
            listOf("0.5x", "1x", "2x", "4x", "8x"),
            labels(listOf(info(Lens.ULTRA_WIDE, 0.5, "0.5x", listOf(2.0)), info(Lens.WIDE, 1.0, "1x", listOf(2.0)),
                info(Lens.TELEPHOTO, 4.0, "4x", listOf(2.0)))),
        )
        assertEquals(
            listOf("0.5x", "1x", "2x", "5x"),
            labels(listOf(info(Lens.ULTRA_WIDE, 0.5, "0.5x", listOf(2.0)), info(Lens.WIDE, 1.0, "1x", listOf(2.0)),
                info(Lens.TELEPHOTO, 5.0, "5x"))),
        )
        assertEquals(
            listOf("0.5x", "1x", "3x"),
            labels(listOf(info(Lens.ULTRA_WIDE, 0.5, "0.5x"), info(Lens.WIDE, 1.0, "1x"), info(Lens.TELEPHOTO, 3.0, "3x"))),
        )
    }

    @Test
    fun testCropThatMatchesARealLensUsesTheLens() {
        // The ultra-wide's 2x crop is "1x": the wide camera wins.
        val steps = LensOption.steps(listOf(info(Lens.ULTRA_WIDE, 0.5, "0.5x", listOf(2.0)), info(Lens.WIDE, 1.0, "1x")))
        assertEquals(listOf(LensOption(Lens.ULTRA_WIDE, label = "0.5x", zoom = 0.5), LensOption(Lens.WIDE, label = "1x")), steps)
    }

    @Test
    fun testNoCropsWhenMagnificationIsUnknown() {
        assertEquals(
            listOf("1x", "Tele"),
            labels(listOf(info(Lens.WIDE, 1.0, "1x"), info(Lens.TELEPHOTO, null, "Tele", listOf(2.0)))),
        )
    }

    private val pro17 = listOf(
        LensOption(Lens.ULTRA_WIDE, label = "0.5x", zoom = 0.5), LensOption(Lens.WIDE, label = "1x"),
        LensOption(Lens.WIDE, crop = 2.0, label = "2x", zoom = 2.0), LensOption(Lens.TELEPHOTO, label = "4x", zoom = 4.0),
        LensOption(Lens.TELEPHOTO, crop = 2.0, label = "8x", zoom = 8.0),
    )

    @Test
    fun testPhotoZoomRangeIsFiveTimesTheLongestLens() {
        assertEquals("5x the 4x lens, not the 8x crop", 0.5..20.0, LensOption.photoZoomRange(pro17))
        assertEquals(
            0.5..5.0,
            LensOption.photoZoomRange(listOf(LensOption(Lens.ULTRA_WIDE, label = "0.5x", zoom = 0.5), LensOption(Lens.WIDE, label = "1x"))),
        )
    }

    @Test
    fun testPhotoLabelsInsertAPinchedZoomBetweenSteps() {
        assertEquals("4x", LensOption.photoLabels(pro17, 4.0).current)
        assertEquals(listOf("0.5x", "1x", "2x", "4x", "8x"), LensOption.photoLabels(pro17, 4.0).all)

        val pinched = LensOption.photoLabels(pro17, 3.24)
        assertEquals("3.2x", pinched.current)
        assertEquals(listOf("0.5x", "1x", "2x", "3.2x", "4x", "8x"), pinched.all)

        assertEquals("13x", LensOption.photoLabels(pro17, 12.6).all.last())
        // Rounds onto a step's label: shown as that step, not twice.
        assertEquals(listOf("0.5x", "1x", "2x", "4x", "8x"), LensOption.photoLabels(pro17, 3.96).all)
    }

    @Test
    fun testFreeZoomLabels() {
        assertEquals("3.2x", LensLabel.formatFree(3.24))
        assertEquals("2x", LensLabel.formatFree(2.0))
        assertEquals("0.7x", LensLabel.formatFree(0.74))
        assertEquals("13x", LensLabel.formatFree(12.6))
    }

    @Test
    fun testResolveFallsBackToTheUncroppedLens() {
        val options = listOf(
            LensOption(Lens.WIDE, label = "1x"), LensOption(Lens.TELEPHOTO, label = "4x"),
            LensOption(Lens.TELEPHOTO, crop = 2.0, label = "8x"),
        )
        assertEquals("8x", LensOption.resolve(Lens.TELEPHOTO, 2.0, options)?.label)
        assertEquals("4x", LensOption.resolve(Lens.TELEPHOTO, 2.0, options.take(2))?.label)
        assertEquals("1x", LensOption.resolve(Lens.ULTRA_WIDE, 1.0, options)?.label)
    }
}

class SettingsStoreTests {
    @Test
    fun testDefaultsAndPersistence() {
        val backing = MapStore()
        val store = SettingsStore(backing)
        assertEquals(
            CaptureSettings(preset = VideoPreset.UHD4K30, lens = Lens.TELEPHOTO, audioEnabled = true, orientation = CaptureOrientation.PORTRAIT),
            store.settings,
        )

        store.settings = store.settings.copy(preset = VideoPreset.UHD4K60)
        store.settings = store.settings.copy(lens = Lens.WIDE)
        store.settings = store.settings.copy(crop = 2.0)
        store.settings = store.settings.copy(audioEnabled = false)
        store.settings = store.settings.copy(orientation = CaptureOrientation.LANDSCAPE)
        store.settings = store.settings.copy(mode = CaptureMode.PHOTO)

        // e.g. after the process was killed and the app relaunched
        val reloaded = SettingsStore(backing)
        assertEquals(
            CaptureSettings(preset = VideoPreset.UHD4K60, lens = Lens.WIDE, crop = 2.0, audioEnabled = false, orientation = CaptureOrientation.LANDSCAPE),
            reloaded.settings,
        )
        assertEquals("always opens in video mode", CaptureMode.VIDEO, reloaded.settings.mode)
    }

    @Test
    fun testRemovedPresetFallsBackToDefault() {
        val backing = MapStore()
        backing.putString("settings.preset", "hd1080p30") // saved by an older build
        assertEquals(VideoPreset.UHD4K30, SettingsStore(backing).settings.preset)
    }

    @Test
    fun testPresetOptions() {
        assertEquals(listOf(VideoPreset.HD1080P60, VideoPreset.UHD4K30, VideoPreset.UHD4K60), VideoPreset.entries.toList())
        assertEquals(60, VideoPreset.UHD4K60.fps)
        assertEquals(3840, VideoPreset.UHD4K60.width)
        assertEquals(1080, VideoPreset.HD1080P60.height)
    }

    @Test
    fun testPresetResolution() {
        val all = listOf(VideoPreset.HD1080P60, VideoPreset.UHD4K30, VideoPreset.UHD4K60)
        assertEquals(VideoPreset.UHD4K60, VideoPreset.resolve(VideoPreset.UHD4K60, all))
        assertEquals(VideoPreset.UHD4K30, VideoPreset.resolve(VideoPreset.UHD4K60, all.take(2)))
        assertEquals(VideoPreset.HD1080P60, VideoPreset.resolve(VideoPreset.UHD4K60, all.take(1)))
        assertNull(VideoPreset.resolve(VideoPreset.UHD4K30, emptyList()))
    }

    @Test
    fun testLensResolution() {
        val pro = listOf(Lens.ULTRA_WIDE, Lens.WIDE, Lens.TELEPHOTO)
        val base = listOf(Lens.ULTRA_WIDE, Lens.WIDE)
        val single = listOf(Lens.WIDE)
        assertEquals(Lens.TELEPHOTO, Lens.resolve(Lens.TELEPHOTO, pro))
        assertEquals(Lens.ULTRA_WIDE, Lens.resolve(Lens.ULTRA_WIDE, pro))
        assertEquals(Lens.WIDE, Lens.resolve(Lens.TELEPHOTO, base))
        assertEquals(Lens.WIDE, Lens.resolve(Lens.ULTRA_WIDE, single))
        assertNull(Lens.resolve(Lens.WIDE, emptyList()))
    }

    @Test
    fun testLegacyLensValuesStillLoad() {
        // Raw values match the iOS app's.
        assertEquals(Lens.WIDE, Lens.fromRaw("wide"))
        assertEquals(Lens.TELEPHOTO, Lens.fromRaw("telephoto"))
        assertEquals(listOf(Lens.ULTRA_WIDE, Lens.WIDE, Lens.TELEPHOTO), Lens.entries.toList())
    }
}
