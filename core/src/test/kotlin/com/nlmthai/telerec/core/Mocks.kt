package com.nlmthai.telerec.core

import java.io.File
import java.time.Instant

class MockCamera : CameraControlling {
    override var onEvent: ((CameraEvent) -> Unit)? = null
    override var lensLabel = "3x"
    override var lensLabels: List<String> = emptyList()
    override var mode: CaptureMode = CaptureMode.VIDEO
    val startedFiles = mutableListOf<File>()
    var stopCount = 0
        private set
    var photoCount = 0
        private set

    override fun startRecording(file: File) {
        startedFiles.add(file)
    }

    override fun stopRecording() {
        stopCount += 1
    }

    override fun capturePhoto() {
        photoCount += 1
    }

    /** Simulates MediaRecorder writing a file and finishing. */
    fun finish(errorMessage: String? = null) {
        val file = startedFiles.lastOrNull() ?: return
        file.writeText("movie")
        onEvent?.invoke(CameraEvent.RecordingFinished(file, errorMessage))
    }

    fun emit(event: CameraEvent) {
        onEvent?.invoke(event)
    }
}

class MockSaver : MediaSaving {
    class Failure : Exception("disk full")

    var shouldFail = false
    val saved = mutableListOf<File>()
    val savedPhotos = mutableListOf<ByteArray>()

    override suspend fun saveVideo(file: File) {
        if (shouldFail) throw Failure()
        saved.add(file)
    }

    override suspend fun savePhoto(data: ByteArray) {
        if (shouldFail) throw Failure()
        savedPhotos.add(data)
    }
}

class MockWatch : WatchChannel {
    override var onCommand: ((WatchCommand) -> Unit)? = null
    val replies = mutableListOf<WatchReply>()
    val last: WatchReply? get() = replies.lastOrNull()

    override fun send(reply: WatchReply) {
        replies.add(reply)
    }

    fun receive(cmd: WatchCommand) {
        onCommand?.invoke(cmd)
    }

    fun reset() = replies.clear()
}

/** Manually advanced clock. */
class TestClock {
    var now: Instant = Instant.ofEpochSecond(1_000_000)
    fun advance(seconds: Double) {
        now = now.plusMillis((seconds * 1000).toLong())
    }
}

class MapStore : KeyValueStore {
    val values = mutableMapOf<String, Any>()
    override fun getString(key: String) = values[key] as? String
    override fun getBoolean(key: String) = values[key] as? Boolean
    override fun getDouble(key: String) = values[key] as? Double
    override fun putString(key: String, value: String) { values[key] = value }
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun putDouble(key: String, value: Double) { values[key] = value }
}
