package com.nlmthai.telerec.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nlmthai.telerec.app.AppModel
import com.nlmthai.telerec.core.CaptureOrientation
import com.nlmthai.telerec.core.VideoPreset

/** Resolution, audio and orientation. Changes apply when the sheet closes, as on iOS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(model: AppModel, onDismiss: () -> Unit) {
    val settings by model.settings.collectAsStateWithLifecycle()
    val supported by model.camera.supportedPresets.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Text("Resolution", style = MaterialTheme.typography.titleSmall)
            for (preset in supported.ifEmpty { listOf(VideoPreset.DEFAULT) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(preset == settings.preset, role = Role.RadioButton) {
                            model.updateSettings { it.copy(preset = preset) }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = preset == settings.preset, onClick = null)
                    Text(preset.title, Modifier.padding(start = 12.dp))
                }
            }
            Text(
                "Only formats the selected lens supports are listed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Record audio", Modifier.weight(1f))
                Switch(checked = settings.audioEnabled, onCheckedChange = { on -> model.updateSettings { it.copy(audioEnabled = on) } })
            }
            Spacer(Modifier.height(16.dp))
            Text("Orientation", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                CaptureOrientation.entries.forEachIndexed { i, o ->
                    SegmentedButton(
                        selected = settings.orientation == o,
                        onClick = { model.updateSettings { it.copy(orientation = o) } },
                        shape = SegmentedButtonDefaults.itemShape(i, CaptureOrientation.entries.size),
                    ) { Text(o.title) }
                }
            }
        }
    }
}
