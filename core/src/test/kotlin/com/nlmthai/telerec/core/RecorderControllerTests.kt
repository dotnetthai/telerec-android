package com.nlmthai.telerec.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RecorderControllerTests {
    private lateinit var camera: MockCamera
    private lateinit var saver: MockSaver
    private lateinit var watch: MockWatch
    private lateinit var clock: TestClock
    private lateinit var tempDir: File
    private val dispatcher = StandardTestDispatcher()
    private lateinit var sut: RecorderController

    @Before
    fun setUp() {
        camera = MockCamera()
        saver = MockSaver()
        watch = MockWatch()
        clock = TestClock()
        tempDir = Files.createTempDirectory("RecorderControllerTests").toFile()
        var n = 0
        sut = RecorderController(
            camera, saver, watch, CoroutineScope(dispatcher),
            now = { clock.now }, makeTempFile = { File(tempDir, "TeleRec-${n++}.mp4") },
        )
        sut.cameraBecameAvailable()
        watch.reset()
    }

    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }

    /** Lets the save coroutine finish (the Swift `settle()` polling loop). */
    private fun TestScope.settle() {
        testScheduler.advanceUntilIdle()
        assertFalse("still saving", sut.state == RecorderState.Saving || sut.state == RecorderState.Capturing)
    }

    private fun startRecording() {
        watch.receive(WatchCommand.Start)
        camera.emit(CameraEvent.RecordingStarted)
    }

    private fun states() = watch.replies.map { it.state }

    // Happy path

    @Test
    fun testFullCycleIdleRecordingSavingIdle() = test {
        assertEquals(RecorderState.Idle, sut.state)

        watch.receive(WatchCommand.Toggle)
        assertEquals(RecorderState.Starting, sut.state)
        assertEquals(1, camera.startedFiles.size)
        assertEquals(WatchReply.State.RECORDING, watch.last?.state)
        assertEquals(0, watch.last?.elapsed)

        camera.emit(CameraEvent.RecordingStarted)
        assertEquals(RecorderState.Recording(clock.now), sut.state)

        clock.advance(42.0)
        watch.receive(WatchCommand.Toggle)
        assertEquals(RecorderState.Saving, sut.state)
        assertEquals(1, camera.stopCount)
        assertEquals(WatchReply.State.SAVING, watch.last?.state)

        val file = camera.startedFiles.first()
        camera.finish()
        settle()

        assertEquals(RecorderState.Idle, sut.state)
        assertEquals(listOf(file), saver.saved)
        assertFalse("temp file deleted after save", file.exists())
        assertEquals(WatchReply.State.IDLE, watch.last?.state)
        assertEquals(listOf(WatchReply.State.RECORDING, WatchReply.State.SAVING, WatchReply.State.IDLE), states())
    }

    @Test
    fun testStatusReportsElapsedAndLens() {
        startRecording()
        clock.advance(42.7)
        watch.reset()

        watch.receive(WatchCommand.Status)
        assertEquals(listOf(WatchReply(WatchReply.State.RECORDING, 42, "3x")), watch.replies)
    }

    @Test
    fun testReplyMessageDictionary() {
        val dict = WatchReply(WatchReply.State.ERROR, 0, "5x", msg = "oops").message
        assertEquals("error", dict["state"])
        assertEquals(0, dict["elapsed"])
        assertEquals("5x", dict["lens"])
        assertEquals("oops", dict["msg"])
        assertNull(WatchReply(WatchReply.State.IDLE, 0, "3x").message["msg"])
        assertNull(WatchReply(WatchReply.State.IDLE, 0, "3x").message["shot"])
    }

    @Test
    fun testParsesWatchCommands() {
        assertEquals(WatchCommand.Toggle, WatchCommand.parse(mapOf("cmd" to "toggle")))
        assertEquals(WatchCommand.Stop, WatchCommand.parse(mapOf("cmd" to "stop")))
        assertNull(WatchCommand.parse(mapOf("cmd" to "jump")))
        assertNull(WatchCommand.parse("start"))
        assertNull(WatchCommand.parse(null))
        assertEquals(WatchCommand.LensChange("0.5x"), WatchCommand.parse(mapOf("cmd" to "lens", "lens" to "0.5x")))
        assertNull(WatchCommand.parse(mapOf("cmd" to "lens")))
    }

    @Test
    fun testParsesTheSdkMessageList() {
        // The Android SDK hands over List<Object> with the watch's Dictionary first.
        assertEquals(WatchCommand.Start, WatchCommand.parse(listOf(mapOf("cmd" to "start"))))
        assertEquals(WatchCommand.Shoot(9), WatchCommand.parse(listOf(hashMapOf<Any, Any>("cmd" to "shoot", "id" to 9L))))
        assertNull(WatchCommand.parse(emptyList<Any>()))
        assertNull(WatchCommand.parse(listOf("start")))
    }

    @Test
    fun testReplyMessageIncludesLensList() {
        val dict = WatchReply(WatchReply.State.IDLE, 0, "1x", lenses = listOf("0.5x", "1x", "3x")).message
        assertEquals(listOf("0.5x", "1x", "3x"), dict["lenses"])
    }

    // Photo mode

    @Test
    fun testParsesPhotoCommands() {
        assertEquals(WatchCommand.ModeChange(CaptureMode.PHOTO), WatchCommand.parse(mapOf("cmd" to "mode", "mode" to "photo")))
        assertNull(WatchCommand.parse(mapOf("cmd" to "mode", "mode" to "slowmo")))
        assertEquals(WatchCommand.Shoot(1234), WatchCommand.parse(mapOf("cmd" to "shoot", "id" to 1234)))
        assertNull(WatchCommand.parse(mapOf("cmd" to "shoot")))
    }

    @Test
    fun testModeRequestSwitchesThenRepliesWithNewMode() {
        val requests = mutableListOf<CaptureMode>()
        sut.onModeRequest = { requests.add(it) }

        watch.receive(WatchCommand.ModeChange(CaptureMode.PHOTO))
        assertEquals(listOf(CaptureMode.PHOTO), requests)
        assertTrue("reply waits for the session", watch.replies.isEmpty())

        camera.mode = CaptureMode.PHOTO
        sut.cameraReconfigured()
        assertEquals(listOf(CaptureMode.PHOTO), watch.replies.map { it.mode })
        assertEquals("photo", watch.last?.message?.get("mode"))
    }

    @Test
    fun testModeCannotChangeWhileRecordingOrSaving() {
        val requests = mutableListOf<CaptureMode>()
        sut.onModeRequest = { requests.add(it) }
        startRecording()
        watch.reset()

        watch.receive(WatchCommand.ModeChange(CaptureMode.PHOTO))
        assertFalse("phone switch is locked too", sut.requestMode(CaptureMode.PHOTO))
        watch.receive(WatchCommand.Stop)
        watch.receive(WatchCommand.ModeChange(CaptureMode.PHOTO))
        assertTrue(requests.isEmpty())
        assertEquals(listOf(WatchReply.State.RECORDING, WatchReply.State.SAVING, WatchReply.State.SAVING), states())
    }

    @Test
    fun testNothingStartsWhileSwitchingModes() {
        sut.onModeRequest = { }
        watch.receive(WatchCommand.ModeChange(CaptureMode.PHOTO))
        watch.receive(WatchCommand.Start)
        assertTrue(camera.startedFiles.isEmpty())
    }

    @Test
    fun testShootTakesAndSavesThenConfirmsTheShot() = test {
        camera.mode = CaptureMode.PHOTO
        watch.receive(WatchCommand.Shoot(7))
        assertEquals(RecorderState.Capturing, sut.state)
        assertEquals(1, camera.photoCount)
        assertEquals(WatchReply.State.SAVING, watch.last?.state)

        camera.emit(CameraEvent.PhotoCaptured("jpeg".toByteArray()))
        settle()
        assertEquals(RecorderState.Idle, sut.state)
        assertEquals(listOf("jpeg"), saver.savedPhotos.map { String(it) })
        assertEquals(WatchReply.State.IDLE, watch.last?.state)
        assertEquals(7, watch.last?.shot)
    }

    @Test
    fun testRetriedShotIsNotTakenTwice() = test {
        camera.mode = CaptureMode.PHOTO
        watch.receive(WatchCommand.Shoot(7))
        watch.receive(WatchCommand.Shoot(7)) // reply lost, watch retries while capturing
        camera.emit(CameraEvent.PhotoCaptured(ByteArray(0)))
        settle()
        watch.reset()

        watch.receive(WatchCommand.Shoot(7)) // retry after it was saved
        assertEquals(1, camera.photoCount)
        assertEquals("retry just gets the confirmation", listOf<Int?>(7), watch.replies.map { it.shot })

        watch.receive(WatchCommand.Shoot(8))
        assertEquals(2, camera.photoCount)
    }

    @Test
    fun testRecordCommandsAreIgnoredInPhotoModeAndShootInVideo() {
        camera.mode = CaptureMode.PHOTO
        watch.receive(WatchCommand.Start)
        watch.receive(WatchCommand.Toggle)
        assertTrue(camera.startedFiles.isEmpty())

        camera.mode = CaptureMode.VIDEO
        watch.receive(WatchCommand.Shoot(1))
        assertEquals(0, camera.photoCount)
        assertEquals(listOf(WatchReply.State.IDLE, WatchReply.State.IDLE, WatchReply.State.IDLE), states())
    }

    @Test
    fun testPhoneButtonIsTheShutterInPhotoMode() {
        camera.mode = CaptureMode.PHOTO
        sut.toggle()
        assertEquals(1, camera.photoCount)
        assertTrue(camera.startedFiles.isEmpty())
        assertNull("phone shots don't confirm a watch press", watch.last?.shot)
    }

    @Test
    fun testPhotoFailureGoesToErrorThenCanRetry() {
        camera.mode = CaptureMode.PHOTO
        watch.receive(WatchCommand.Shoot(1))
        camera.emit(CameraEvent.PhotoFailed("Camera not ready"))
        assertEquals(RecorderState.Error("Camera not ready"), sut.state)

        watch.receive(WatchCommand.Shoot(2))
        assertEquals(2, camera.photoCount)
    }

    // Lens switching from the watch

    @Test
    fun testLensRequestSwitchesThenRepliesWithNewLens() {
        camera.lensLabels = listOf("0.5x", "1x", "3x")
        val requests = mutableListOf<String>()
        sut.onLensRequest = { requests.add(it) }

        watch.receive(WatchCommand.LensChange("1x"))
        assertEquals(listOf("1x"), requests)
        assertTrue("reply waits for the new lens", watch.replies.isEmpty())

        camera.lensLabel = "1x"
        sut.cameraReconfigured()
        assertEquals(listOf(WatchReply(WatchReply.State.IDLE, 0, "1x", lenses = listOf("0.5x", "1x", "3x"))), watch.replies)
    }

    @Test
    fun testLensRequestIsAnsweredEvenIfTheSwitchFailed() {
        camera.lensLabels = listOf("0.5x", "1x", "3x")
        sut.onLensRequest = { }
        watch.receive(WatchCommand.Status)
        watch.reset()

        watch.receive(WatchCommand.LensChange("1x"))
        sut.cameraReconfigured() // configure failed; still on 3x
        assertEquals(listOf("3x"), watch.replies.map { it.lens })
    }

    @Test
    fun testLensCannotChangeWhileRecordingOrSaving() {
        camera.lensLabels = listOf("0.5x", "1x", "3x")
        val requests = mutableListOf<String>()
        sut.onLensRequest = { requests.add(it) }
        startRecording()
        watch.reset()

        watch.receive(WatchCommand.LensChange("1x"))
        watch.receive(WatchCommand.Stop)
        watch.receive(WatchCommand.LensChange("1x"))
        assertTrue(requests.isEmpty())
        assertEquals(listOf(WatchReply.State.RECORDING, WatchReply.State.SAVING, WatchReply.State.SAVING), states())
    }

    @Test
    fun testCurrentOrUnknownLensJustReplies() {
        camera.lensLabels = listOf("0.5x", "1x", "3x")
        val requests = mutableListOf<String>()
        sut.onLensRequest = { requests.add(it) }

        watch.receive(WatchCommand.LensChange("3x"))
        watch.receive(WatchCommand.LensChange("8x"))
        assertTrue(requests.isEmpty())
        assertEquals(listOf("3x", "3x"), watch.replies.map { it.lens })
    }

    @Test
    fun testLensChangedOnPhoneIsPushedToWatch() {
        camera.lensLabels = listOf("0.5x", "1x", "3x")
        sut.cameraReconfigured()
        watch.reset()

        sut.cameraReconfigured() // e.g. settings sheet closed, nothing changed
        assertTrue(watch.replies.isEmpty())

        camera.lensLabel = "0.5x"
        sut.cameraReconfigured()
        assertEquals(listOf("0.5x"), watch.replies.map { it.lens })
    }

    @Test
    fun testWatchGoingSilentDoesNotAffectRecording() {
        // Watch disconnects (out of range, app closed, battery dead): the phone
        // hears nothing, and must keep recording.
        startRecording()
        clock.advance(3600.0)
        assertEquals(RecorderState.Recording(clock.now.minusSeconds(3600)), sut.state)
        assertEquals(0, camera.stopCount)

        // Watch reconnects and asks for status: it gets the real elapsed time.
        watch.reset()
        watch.receive(WatchCommand.Status)
        assertEquals(listOf(WatchReply(WatchReply.State.RECORDING, 3600, "3x")), watch.replies)
    }

    // No-ops still reply

    @Test
    fun testStartWhileRecordingIsNoOpButReplies() {
        startRecording()
        watch.reset()

        watch.receive(WatchCommand.Start)
        assertEquals(1, camera.startedFiles.size)
        assertEquals(listOf(WatchReply.State.RECORDING), states())
    }

    @Test
    fun testStopWhileIdleIsNoOpButReplies() {
        watch.receive(WatchCommand.Stop)
        assertEquals(0, camera.stopCount)
        assertEquals(RecorderState.Idle, sut.state)
        assertEquals(listOf(WatchReply.State.IDLE), states())
    }

    @Test
    fun testCommandsWhileSavingAreIgnored() {
        startRecording()
        watch.receive(WatchCommand.Stop)
        watch.reset()

        watch.receive(WatchCommand.Toggle)
        watch.receive(WatchCommand.Start)
        assertEquals(RecorderState.Saving, sut.state)
        assertEquals(1, camera.startedFiles.size)
        assertEquals(listOf(WatchReply.State.SAVING, WatchReply.State.SAVING), states())
    }

    @Test
    fun testStopBeforeOutputConfirmsStart() = test {
        watch.receive(WatchCommand.Start)
        watch.receive(WatchCommand.Stop)
        assertEquals(RecorderState.Saving, sut.state)
        camera.emit(CameraEvent.RecordingStarted) // late confirmation must not resurrect recording
        assertEquals(RecorderState.Saving, sut.state)
        camera.finish()
        settle()
        assertEquals(RecorderState.Idle, sut.state)
    }

    // Interruptions

    @Test
    fun testInterruptionWhileRecordingStopsAndSaves() = test {
        startRecording()
        watch.reset()

        camera.emit(CameraEvent.Interrupted("Camera in use by another app"))
        assertEquals(RecorderState.Saving, sut.state)
        assertEquals(1, camera.stopCount)
        assertEquals(WatchReply.State.SAVING, watch.last?.state)

        camera.finish()
        settle()
        assertEquals(1, saver.saved.size)
        // Still interrupted: watch is told why it can't record.
        assertEquals(WatchReply(WatchReply.State.ERROR, 0, "3x", msg = "Camera in use by another app"), watch.last)

        camera.emit(CameraEvent.InterruptionEnded)
        assertEquals(WatchReply.State.IDLE, watch.last?.state)
    }

    /** Android keeps recording in the background; the camera only goes away when the service stops. */
    @Test
    fun testCameraClosedWhileRecordingStopsSavesAndNotifiesWatch() = test {
        startRecording()
        watch.reset()

        sut.cameraBecameUnavailable()
        assertEquals(RecorderState.Saving, sut.state)
        assertEquals(1, camera.stopCount)
        assertEquals(WatchReply.State.SAVING, watch.last?.state)

        camera.finish()
        settle()
        assertEquals(1, saver.saved.size)
        assertEquals(WatchReply.State.ERROR, watch.last?.state)
        assertEquals(RecorderController.UNAVAILABLE_MESSAGE, watch.last?.msg)
    }

    @Test
    fun testStartWhileUnavailableRepliesErrorAndDoesNotRecord() {
        sut.cameraBecameUnavailable()
        watch.reset()

        watch.receive(WatchCommand.Start)
        assertTrue(camera.startedFiles.isEmpty())
        assertEquals(
            listOf(WatchReply(WatchReply.State.ERROR, 0, "3x", msg = RecorderController.UNAVAILABLE_MESSAGE)),
            watch.replies,
        )
    }

    @Test
    fun testUnavailableMessageSaysPhoneNotIPhone() {
        assertEquals("Open TeleRec on phone", RecorderController.UNAVAILABLE_MESSAGE)
    }

    @Test
    fun testOutputEndingRecordingOnItsOwnIsSaved() = test {
        // e.g. the max file size was reached before we heard about it
        startRecording()
        camera.finish()
        assertEquals(RecorderState.Saving, sut.state)
        settle()
        assertEquals(RecorderState.Idle, sut.state)
        assertEquals(1, saver.saved.size)
    }

    // Errors

    @Test
    fun testRecordingFailureGoesToErrorThenCanRetry() {
        watch.receive(WatchCommand.Start)
        camera.finish(errorMessage = "Cannot record")
        assertEquals(RecorderState.Error("Cannot record"), sut.state)
        assertEquals("Cannot record", watch.last?.msg)

        watch.receive(WatchCommand.Start)
        assertEquals(RecorderState.Starting, sut.state)
        assertEquals(2, camera.startedFiles.size)
    }

    @Test
    fun testSaveFailureGoesToError() = test {
        saver.shouldFail = true
        startRecording()
        watch.receive(WatchCommand.Stop)
        val file = camera.startedFiles.first()
        camera.finish()
        settle()

        val state = sut.state
        assertTrue("expected error, got $state", state is RecorderState.Error && "disk full" in state.message)
        assertEquals(WatchReply.State.ERROR, watch.last?.state)
        assertTrue("footage kept after a failed save", file.exists())
    }

    @Test
    fun testRuntimeErrorWhileRecordingStops() {
        startRecording()
        camera.emit(CameraEvent.RuntimeError("Camera service died"))
        assertEquals(RecorderState.Saving, sut.state)
        assertEquals(1, camera.stopCount)
    }
}
