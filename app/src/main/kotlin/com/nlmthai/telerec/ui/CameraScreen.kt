package com.nlmthai.telerec.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nlmthai.telerec.app.AppModel
import com.nlmthai.telerec.core.CaptureMode
import com.nlmthai.telerec.core.Lens
import com.nlmthai.telerec.core.RecorderState
import com.nlmthai.telerec.watch.ConnectIqService
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant

private val Yellow = Color(0xFFFFD60A)
private val Scrim = Color.Black.copy(alpha = 0.5f)

@Composable
fun CameraScreen(model: AppModel, cameraGranted: Boolean, openAppSettings: () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (cameraGranted) CameraContent(model) else PermissionNeeded(openAppSettings)
        }
    }
}

@Composable
private fun PermissionNeeded(openAppSettings: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TeleRec needs the camera to record for your watch.", color = Color.White, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = openAppSettings) { Text("Open Settings") }
    }
}

@Composable
private fun CameraContent(model: AppModel) {
    val camera = model.camera
    val recorder = model.recorder
    val state by recorder.stateFlow.collectAsStateWithLifecycle()
    val available by recorder.isCameraAvailableFlow.collectAsStateWithLifecycle()
    val mode by camera.modeFlow.collectAsStateWithLifecycle()
    val lenses by camera.availableLenses.collectAsStateWithLifecycle()
    val activeOption by camera.activeOption.collectAsStateWithLifecycle()
    val photoZoom by camera.photoZoom.collectAsStateWithLifecycle()
    val previewSize by camera.previewSize.collectAsStateWithLifecycle()
    val setupError by model.setupError.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    val isPhoto = mode == CaptureMode.PHOTO
    val busy = recorder.isBusy

    Box(Modifier.fillMaxSize()) {
        CameraPreview(
            camera.previewTexture, previewSize,
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                // Free zoom is photo mode only; video stays on full-quality steps.
                .pointerInput(isPhoto) {
                    if (!isPhoto) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var zoomed = false
                        do {
                            val event = awaitPointerEvent()
                            val z = event.calculateZoom()
                            if (z != 1f) {
                                camera.setPhotoZoom(camera.photoZoom.value * z)
                                zoomed = true
                            }
                        } while (event.changes.any { it.pressed })
                        if (zoomed) recorder.cameraReconfigured() // show the new zoom on the watch
                    }
                },
        )

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TopBar(model, settingsEnabled = !busy, onSettings = { showSettings = true })
            LensPicker(lenses, activeOption?.id, locked = busy) { model.select(it) }
            val longest = lenses.lastOrNull()
            if (!isPhoto && longest != null && lenses.none { it.lens == Lens.TELEPHOTO } && setupError == null) {
                val why = if (model.supportedPhone == null) {
                    "${model.phoneName} isn't on TeleRec's supported list, so video uses the main lens only"
                } else {
                    "No telephoto can record video here, so ${longest.label} is the longest full-quality zoom"
                }
                Banner(why, Color(0xFFFF9500))
            }
            (setupError ?: (state as? RecorderState.Error)?.message)?.let { Banner(it, Color(0xFFFF3B30)) }
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (isPhoto) {
                    // Photo mode shows the zoom instead of a timer.
                    Pill(if (photoZoom > 0) camera.lensLabel else "", 28)
                } else {
                    Elapsed(state)
                }
            }
            ModePicker(mode, locked = busy) { recorder.requestMode(it) }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RecordButton(
                    isPhoto = isPhoto,
                    state = state,
                    isRecording = recorder.isRecording,
                    enabled = state != RecorderState.Saving && state != RecorderState.Capturing &&
                        (recorder.isRecording || available),
                ) { recorder.toggle() }
            }
        }
    }

    if (showSettings) {
        SettingsSheet(model) {
            showSettings = false
            model.settingsDismissed()
        }
    }
}

@Composable
private fun TopBar(model: AppModel, settingsEnabled: Boolean, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WatchIndicator(model)
        Spacer(Modifier.weight(1f))
        Text(
            "⚙",
            fontSize = 26.sp,
            color = Color.White,
            modifier = Modifier
                .alpha(if (settingsEnabled) 1f else 0.4f)
                .clickable(enabled = settingsEnabled, role = Role.Button, onClick = onSettings)
                .semantics { contentDescription = "Settings" }
                .padding(8.dp),
        )
    }
}

@Composable
private fun WatchIndicator(model: AppModel) {
    val status by model.watch.status.collectAsStateWithLifecycle()
    val name by model.watch.deviceName.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf(false) }
    val color = when (status) {
        ConnectIqService.Status.CONNECTED -> Color(0xFF34C759)
        ConnectIqService.Status.APP_NOT_INSTALLED, ConnectIqService.Status.STARTING -> Color(0xFFFF9500)
        else -> Color(0xFFFF3B30)
    }
    Box {
        Row(
            Modifier
                .background(Scrim, CircleShape)
                .clickable { menu = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(
                name?.let { if (status == ConnectIqService.Status.CONNECTED) it else "$it: ${status.title}" } ?: status.title,
                color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 240.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Refresh watches") }, onClick = {
                menu = false
                model.watch.refreshDevices()
            })
            if (status == ConnectIqService.Status.APP_NOT_INSTALLED) {
                DropdownMenuItem(text = { Text("Install TeleRec on the watch from the Connect IQ Store") }, onClick = { menu = false })
            }
            DropdownMenuItem(text = { Text("Lens info") }, onClick = {
                menu = false
                probe = true
            })
        }
    }
    if (probe) {
        val report by model.camera.probeReport.collectAsStateWithLifecycle()
        AlertDialog(
            onDismissRequest = { probe = false },
            confirmButton = { TextButton(onClick = { probe = false }) { Text("Done") } },
            title = { Text(model.phoneName) },
            text = { Text(report.ifEmpty { "The camera hasn't been opened yet." }, fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
        )
    }
}

/** One button per lens (e.g. 0.5x · 1x · 5x). Locked while recording. */
@Composable
private fun LensPicker(
    lenses: List<com.nlmthai.telerec.core.LensOption>, activeId: String?, locked: Boolean,
    onSelect: (com.nlmthai.telerec.core.LensOption) -> Unit,
) {
    if (lenses.isEmpty()) return
    Row(
        Modifier.background(Scrim, CircleShape).padding(2.dp).alpha(if (locked) 0.6f else 1f),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (option in lenses) {
            val selected = option.id == activeId
            Text(
                option.label,
                color = if (selected) Color.Black else Color.White,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .background(if (selected) Yellow else Color.Transparent, CircleShape)
                    .clickable(enabled = !locked && !selected, role = Role.Button) { onSelect(option) }
                    .semantics {
                        contentDescription = "Lens ${option.label}"
                        this.selected = selected
                    }
                    .widthIn(min = 44.dp)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        fontSize = 13.sp,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().background(color.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(8.dp),
    )
}

@Composable
private fun Pill(text: String, size: Int, background: Color = Scrim, alpha: Float = 1f) {
    Text(
        text,
        color = Color.White,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.alpha(alpha).background(background, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp),
    )
}

@Composable
private fun Elapsed(state: RecorderState) {
    when (state) {
        is RecorderState.Recording -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(state.since) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(250)
                }
            }
            Pill(formatElapsed(Duration.between(state.since, Instant.ofEpochMilli(now)).seconds), 34, Color(0xFFFF3B30))
        }
        RecorderState.Saving -> Text("Saving…", color = Color.White, fontSize = 20.sp)
        else -> Pill(formatElapsed(0), 34, Color.Transparent, alpha = 0.6f)
    }
}

fun formatElapsed(seconds: Long): String {
    val s = maxOf(0, seconds)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}

/** VIDEO | PHOTO, like the watch's hold-DOWN. Locked while busy. */
@Composable
private fun ModePicker(mode: CaptureMode, locked: Boolean, onSelect: (CaptureMode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (locked) 0.6f else 1f),
        horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
    ) {
        for (m in CaptureMode.entries) {
            val selected = m == mode
            Text(
                m.title,
                color = if (selected) Yellow else Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clickable(enabled = !locked, role = Role.Button) { onSelect(m) }
                    .semantics { this.selected = selected }
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun RecordButton(isPhoto: Boolean, state: RecorderState, isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = when {
        isPhoto -> "Take photo"
        isRecording -> "Stop recording"
        else -> "Start recording"
    }
    Box(
        Modifier
            .padding(bottom = 8.dp)
            .size(84.dp)
            .border(5.dp, Color.White, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isPhoto -> Box(
                Modifier.size(68.dp).alpha(if (state == RecorderState.Capturing) 0.4f else 1f).background(Color.White, CircleShape),
            )
            isRecording -> Box(Modifier.size(36.dp).background(Color.Red, RoundedCornerShape(8.dp)))
            else -> Box(Modifier.size(68.dp).background(Color.Red, CircleShape))
        }
    }
}
