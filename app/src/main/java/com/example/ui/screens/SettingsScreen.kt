package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BeamSettings
import com.example.data.QualityPreset
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFontFamily

@Composable
fun SettingsScreen(
    settings: BeamSettings,
    onUpdateTvName: (String) -> Unit,
    onUpdateQualityPreset: (QualityPreset) -> Unit,
    onUpdateRequirePin: (Boolean) -> Unit,
    onRegeneratePin: () -> Unit,
    onUpdateKeepScreenAwake: (Boolean) -> Unit,
    onUpdateLowLatencyMode: (Boolean) -> Unit,
    onUpdateAutoStartReceiver: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var nameInput by remember { mutableStateOf(settings.tvDisplayName) }
    LaunchedEffect(settings.tvDisplayName) {
        nameInput = settings.tvDisplayName
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            // TV Display Identity Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_device_name_label),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = nameInput,
                            onValueChange = { if (it.length <= 28) nameInput = it },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("tv_name_input")
                        )
                        Button(
                            onClick = { onUpdateTvName(nameInput) },
                            enabled = nameInput.isNotBlank() && nameInput != settings.tvDisplayName,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("save_tv_name_button")
                        ) {
                            Text("Save")
                        }
                    }
                }
            }

            // Encoding Quality Preset Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_quality_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    QualityPreset.entries.forEach { preset ->
                        val selected = preset == settings.qualityPreset
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            border = BorderStroke(
                                1.dp,
                                if (selected) ElectricCyan.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUpdateQualityPreset(preset) }
                                .testTag("settings_quality_${preset.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { onUpdateQualityPreset(preset) }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = preset.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = preset.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Security & Display Toggles Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                        RoundedCornerShape(20.dp)
                    ),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SettingsToggleRow(
                        icon = Icons.Rounded.Lock,
                        title = stringResource(R.string.settings_pin_title),
                        subtitle = stringResource(R.string.settings_pin_subtitle),
                        checked = settings.requirePin,
                        onCheckedChange = onUpdateRequirePin,
                        testTag = "toggle_require_pin"
                    )

                    if (settings.requirePin) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "ACTIVE PAIRING PIN",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = settings.pairingPin,
                                        style = MaterialTheme.typography.headlineSmall.copy(
                                            fontFamily = JetBrainsMonoFontFamily,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = ElectricCyan
                                    )
                                }
                                OutlinedButton(
                                    onClick = onRegeneratePin,
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("regenerate_pin_button")
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("New PIN")
                                }
                            }
                        }
                    }

                    SettingsToggleRow(
                        icon = Icons.Rounded.ScreenLockPortrait,
                        title = stringResource(R.string.settings_wake_title),
                        subtitle = stringResource(R.string.settings_wake_subtitle),
                        checked = settings.keepScreenAwake,
                        onCheckedChange = onUpdateKeepScreenAwake,
                        testTag = "toggle_keep_awake"
                    )

                    SettingsToggleRow(
                        icon = Icons.Rounded.Bolt,
                        title = stringResource(R.string.settings_low_latency_title),
                        subtitle = stringResource(R.string.settings_low_latency_subtitle),
                        checked = settings.lowLatencyMode,
                        onCheckedChange = onUpdateLowLatencyMode,
                        testTag = "toggle_low_latency"
                    )

                    SettingsToggleRow(
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = stringResource(R.string.settings_auto_start_title),
                        subtitle = stringResource(R.string.settings_auto_start_subtitle),
                        checked = settings.autoStartReceiver,
                        onCheckedChange = onUpdateAutoStartReceiver,
                        testTag = "toggle_auto_start"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 4.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
