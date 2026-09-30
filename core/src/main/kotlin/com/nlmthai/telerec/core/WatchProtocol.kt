package com.nlmthai.telerec.core

/** Connect IQ app id shared by the watch app (manifest.xml) and this companion app. */
object TeleRecIDs {
    const val CONNECT_IQ_APP_UUID = "70cea3b2-1a8a-4cc5-bc23-76c375279e79"

    /** The Android SDK takes the UUID without dashes, as in the watch's manifest.xml. */
    val connectIQAppId: String get() = CONNECT_IQ_APP_UUID.replace("-", "")
}

/**
 * Watch → phone: `{ "cmd": "toggle" | "start" | "stop" | "status" }`,
 * `{ "cmd": "lens", "lens": "3x" }` (a label from the reply's `lenses`),
 * `{ "cmd": "mode", "mode": "photo" }` or `{ "cmd": "shoot", "id": 123 }`.
 */
sealed interface WatchCommand {
    data object Toggle : WatchCommand
    data object Start : WatchCommand
    data object Stop : WatchCommand
    data object Status : WatchCommand

    /** Names the target lens rather than "next", so a retry can't skip one. */
    data class LensChange(val label: String) : WatchCommand

    /** Names the target mode rather than "switch", for the same reason. */
    data class ModeChange(val mode: CaptureMode) : WatchCommand

    /** `id` is unique per press, so a retry of a shot that got through isn't taken twice. */
    data class Shoot(val id: Int) : WatchCommand

    companion object {
        /**
         * Parses one message. The Android SDK delivers a `List<Object>` whose
         * first element is the watch's Dictionary; a bare `Map` is accepted too.
         */
        fun parse(message: Any?): WatchCommand? {
            val dict = when (message) {
                is Map<*, *> -> message
                is List<*> -> message.firstOrNull() as? Map<*, *>
                else -> null
            } ?: return null
            return when (dict["cmd"] as? String) {
                "toggle" -> Toggle
                "start" -> Start
                "stop" -> Stop
                "status" -> Status
                "lens" -> (dict["lens"] as? String)?.let { LensChange(it) }
                "mode" -> CaptureMode.fromRaw(dict["mode"] as? String)?.let { ModeChange(it) }
                // Monkey C numbers arrive as Integer or Long; a Double would be a different message.
                "shoot" -> when (val id = dict["id"]) {
                    is Int -> Shoot(id)
                    is Long -> if (id in Int.MIN_VALUE..Int.MAX_VALUE) Shoot(id.toInt()) else null
                    else -> null
                }
                else -> null
            }
        }
    }
}

/**
 * Phone → watch: `{ "state": ..., "elapsed": Int, "lens": "3x", "lenses": ["0.5x", "1x", "3x"],
 * "mode": "video" | "photo", "shot": Int?, "msg": String? }`
 */
data class WatchReply(
    val state: State,
    val elapsed: Int,
    val lens: String,
    val msg: String? = null,
    /** Every lens this phone offers, widest first. */
    val lenses: List<String> = emptyList(),
    val mode: CaptureMode = CaptureMode.VIDEO,
    /** The last watch shot saved to the gallery; the watch buzzes when it matches its press. */
    val shot: Int? = null,
) {
    enum class State(val rawValue: String) { IDLE("idle"), RECORDING("recording"), SAVING("saving"), ERROR("error") }

    val message: Map<String, Any>
        get() {
            val dict = mutableMapOf<String, Any>(
                "state" to state.rawValue, "elapsed" to elapsed, "lens" to lens, "lenses" to lenses,
                "mode" to mode.rawValue,
            )
            msg?.let { dict["msg"] = it }
            shot?.let { dict["shot"] = it }
            return dict
        }
}
