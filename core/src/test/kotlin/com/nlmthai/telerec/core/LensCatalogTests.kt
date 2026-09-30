package com.nlmthai.telerec.core

import com.nlmthai.telerec.core.LensCatalog.Access
import com.nlmthai.telerec.core.LensCatalog.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedPhonesTests {
    @Test
    fun testPixelProModels() {
        assertEquals("Pixel 8 Pro", SupportedPhones.match("Google", "Pixel 8 Pro")?.name)
        assertEquals("Pixel 9 Pro XL", SupportedPhones.match("Google", "Pixel 9 Pro XL")?.name)
        assertEquals("Pixel 10 Pro", SupportedPhones.match("Google", "Pixel 10 Pro")?.name)
        assertNull(SupportedPhones.match("Google", "Pixel 9"))
        assertNull(SupportedPhones.match("Google", "Pixel 9 Pro Fold"))
        assertNull(SupportedPhones.match("Google", "Pixel 8a"))
    }

    @Test
    fun testGalaxyUltraModelCodesWithRegionSuffixes() {
        assertEquals("Galaxy S23 Ultra", SupportedPhones.match("samsung", "SM-S918B")?.name)
        assertEquals("Galaxy S24 Ultra", SupportedPhones.match("samsung", "SM-S928U1")?.name)
        assertEquals("Galaxy S25 Ultra", SupportedPhones.match("samsung", "SM-S9380")?.name)
        assertNull(SupportedPhones.match("samsung", "SM-S921B"), "S24 base model")
        assertNull(SupportedPhones.match("OnePlus", "SM-S918B"), "manufacturer must match")
    }

    private fun assertNull(value: Any?, message: String) = org.junit.Assert.assertNull(message, value)
}

class LensCatalogTests {
    private val all = VideoPreset.entries.toList()
    private val main = Candidate("0", null, Access.STANDALONE, 69.4, all)

    private fun c(id: String, fov: Double?, access: Access = Access.PHYSICAL, presets: List<VideoPreset> = all, logical: String = "0") =
        if (access == Access.STANDALONE) Candidate(id, null, access, fov, presets) else Candidate(logical, id, access, fov, presets)

    private fun labels(entries: List<LensCatalog.Entry>) = entries.map { it.info.label }

    @Test
    fun testTripleCameraThroughPhysicalStreams() {
        // Pixel Pro style: one logical camera 0 with physical 2 (ultra-wide), 3 (main), 4 (5x tele).
        val entries = LensCatalog.build(main, listOf(c("2", 106.0), c("3", 69.4), c("4", 15.8)), supportedPhone = true)
        assertEquals(listOf("0.5x", "1x", "5x"), labels(entries))
        assertEquals(listOf(Lens.ULTRA_WIDE, Lens.WIDE, Lens.TELEPHOTO), entries.map { it.info.lens })
        assertEquals("4", entries.last().candidate.physicalId)
        assertEquals("the main lens opens the default camera", main, entries[1].candidate)
    }

    @Test
    fun testUnsupportedPhoneOffersOnlyTheMainLens() {
        val entries = LensCatalog.build(main, listOf(c("2", 106.0), c("4", 15.8)), supportedPhone = false)
        assertEquals(listOf("1x"), labels(entries))
    }

    @Test
    fun testStandaloneIdBeatsPhysicalStreamOfTheSameLens() {
        val entries = LensCatalog.build(
            main, listOf(c("21", 106.0), c("2", 105.0, Access.STANDALONE)), supportedPhone = true,
        )
        assertEquals(Access.STANDALONE, entries.first().candidate.access)
        assertEquals("2", entries.first().candidate.cameraId)
    }

    @Test
    fun testNarrowestTelephotoWins() {
        // Galaxy Ultra style: 3x and 10x telephotos.
        val entries = LensCatalog.build(main, listOf(c("3", 25.3), c("4", 7.9)), supportedPhone = true)
        assertEquals(listOf("1x", "10x"), labels(entries))
    }

    @Test
    fun testLensesWithoutVideoPresetsOrFieldOfViewAreLeftOut() {
        val entries = LensCatalog.build(
            main, listOf(c("2", 106.0, presets = emptyList()), c("4", null)), supportedPhone = true,
        )
        assertEquals(listOf("1x"), labels(entries))
    }

    @Test
    fun testMainWithoutFieldOfViewCantLabelOthers() {
        val entries = LensCatalog.build(main.copy(fieldOfView = null), listOf(c("2", 106.0)), supportedPhone = true)
        assertEquals(listOf("1x"), labels(entries))
    }

    @Test
    fun testCatalogFeedsZoomSteps() {
        val entries = LensCatalog.build(main, listOf(c("2", 106.0), c("4", 15.8)), supportedPhone = true)
        val steps = LensOption.steps(entries.map { it.info })
        assertEquals(listOf("0.5x", "1x", "5x"), steps.map { it.label })
        val range = LensOption.photoZoomRange(steps)
        assertEquals(steps.first().zoom, range.start, 1e-9)
        assertEquals(5 * steps.last().zoom, range.endInclusive, 1e-9)
    }
}

class OneInFlightTests {
    @Test
    fun testNewerReplyReplacesTheQueuedOne() {
        val sent = mutableListOf<Int>()
        val q = OneInFlight<Int> { sent.add(it) }
        q.send(1)
        q.send(2)
        q.send(3)
        assertEquals(listOf(1), sent)
        assertTrue(q.sending)
        assertEquals(3, q.pending)

        q.completed()
        assertEquals(listOf(1, 3), sent)
        q.completed()
        assertFalse(q.sending)
        assertNull(q.pending)

        q.send(4)
        assertEquals(listOf(1, 3, 4), sent)
    }
}
