package com.nlmthai.telerec.core

import java.io.File

/** Events the camera reports back to the recorder. Delivered on the main thread. */
sealed interface CameraEvent {
    data object RecordingStarted : CameraEvent

    /** `errorMessage` is non-null only when the file is unusable. */
    data class RecordingFinished(val file: File, val errorMessage: String? = null) : CameraEvent

    /** The camera stopped delivering frames (another app took it, the phone is too hot, etc.). */
    data class Interrupted(val reason: String) : CameraEvent
    data object InterruptionEnded : CameraEvent
    data class RuntimeError(val message: String) : CameraEvent

    /** Photo mode: the encoded photo (JPEG), ready to save. */
    class PhotoCaptured(val data: ByteArray) : CameraEvent
    data class PhotoFailed(val message: String) : CameraEvent
}

interface CameraControlling {
    var onEvent: ((CameraEvent) -> Unit)?
    val lensLabel: String

    /** Labels of every lens this phone offers, widest first. */
    val lensLabels: List<String>

    /** The mode the session is actually configured for. */
    val mode: CaptureMode
    fun startRecording(file: File)
    fun stopRecording()
    fun capturePhoto()
}

interface MediaSaving {
    suspend fun saveVideo(file: File)
    suspend fun savePhoto(data: ByteArray)
}

interface WatchChannel {
    var onCommand: ((WatchCommand) -> Unit)?
    fun send(reply: WatchReply)
}
