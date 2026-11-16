package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.BeamSettings
import com.example.data.DiscoveredTvPeer
import com.example.data.PeerTransport
import com.example.data.QualityPreset
import com.example.data.SenderState
import com.example.p2p.StreamProtocol
import com.example.ui.components.PermissionRequestBanner
import com.example.ui.components.SignalPulseDot
import com.example.ui.components.TelemetryPill
import com.example.ui.theme.BroadcastCoral
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.IndigoSignal
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiveEmerald

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CastSenderScreen(
    senderState: SenderState,
    discoveredPeers: List<DiscoveredTvPeer>,
    isScanning: Boolean,
    p2pStatusMessage: String?,
    settings: BeamSettings,
    hasDiscoveryPermission: Boolean,
    onRequestDiscoveryPermission: () -> Unit,
    onScanPeers: () -> Unit,
    onSelectQuality: (QualityPreset) -> Unit,
    onConnectPeer: (DiscoveredTvPeer, String) -> Unit,
    onConnectDirectIp: (String, Int, String) -> Unit,
    onTogglePrivacyPause: () -> Unit,
    onStopCasting: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var manualIp by remember { mutableStateOf("192.168.49.1") }
    var manualPin by remember { mutableStateOf("") }
    var peerRequiringPin by remember { mutableStateOf<DiscoveredTvPeer?>(null) }
    var dialogPinInput by remember { mutableStateOf("") }

    peerRequiringPin?.let { targetPeer ->
        AlertDialog(
            onDismissRequest = { peerRequiringPin = null },
            title = { Text("Enter TV Pairing PIN") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${targetPeer.name} requires a 4-digit PIN before mirroring.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = dialogPinInput,
                        onValueChange = { if (it.length <= 6) dialogPinInput = it.filter(Char::isDigit) },
                        label = { Text("4-digit PIN") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_dialog_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pin = dialogPinInput
                        peerRequiringPin = null
                        dialogPinInput = ""
                        onConnectPeer(targetPeer, pin)
                    },
                    modifier = Modifier.testTag("pin_dialog_confirm")
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { peerRequiringPin = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .widthIn(max = 720.dp)
                .align(Alignment.TopCenter),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.cast_header_title),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.cast_header_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onScanPeers,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("scan_peers_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ElectricCyan,
                            contentColor = Color(0xFF002A33)
                        )
                    ) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF002A33)
                            )
                        } else {
                            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isScanning) {
                                stringResource(R.string.cast_scanning)
                            } else {
                                stringResource(R.string.cast_scan_peers)
                            }
                        )
                    }
                }
            }

            if (!hasDiscoveryPermission) {
                item {
                    PermissionRequestBanner(onRequestPermission = onRequestDiscoveryPermission)
                }
            }

            if (!p2pStatusMessage.isNullOrBlank()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Text(
                            text = p2pStatusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp)
                        )
                    }
                }
            }

            // Active Casting Card or Error Card
            when (senderState) {
                is SenderState.Casting -> {
                    item {
                        ActiveCastStudioCard(
                            state = senderState,
                            onTogglePrivacyPause = onTogglePrivacyPause,
                            onStopCasting = onStopCasting
                        )
                    }
                }
                is SenderState.Connecting,
                is SenderState.AwaitingConsent -> {
                    val target = (senderState as? SenderState.Connecting)?.targetName
                        ?: (senderState as? SenderState.AwaitingConsent)?.targetName
                        ?: "TV Screen"
                    item {
                        Card(
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                            ),
                            border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(18.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = ElectricCyan,
                                    strokeWidth = 2.5.dp
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (senderState is SenderState.AwaitingConsent) {
                                            "Approve screen capture for $target"
                                        } else {
                                            "Establishing P2P link to $target…"
                                        },
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Starting low-latency H.264 encoder",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = onStopCasting) {
                                    Text("Cancel")
                                }
                            }
                        }
                    }
                }
                is SenderState.Error -> {
                    item {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = senderState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = onDismissError) {
                                    Text("Dismiss")
                                }
                            }
                        }
                    }
                }
                SenderState.Idle -> Unit
            }

            // Stream Quality Profile Chips
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.cast_quality_label).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        QualityPreset.entries.forEach { preset ->
                            val selected = preset == settings.qualityPreset
                            FilterChip(
                                selected = selected,
                                onClick = { onSelectQuality(preset) },
                                label = { Text(preset.label) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = ElectricCyan
                                ),
                                modifier = Modifier.testTag("quality_chip_${preset.id}")
                            )
                        }
                    }
                }
            }

            // Discovered Peers Header
            item {
                Text(
                    text = stringResource(R.string.cast_discovered_header).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (discoveredPeers.isEmpty()) {
                item {
                    EmptyPeersCard(isScanning = isScanning)
                }
            } else {
                items(discoveredPeers, key = { it.id }) { peer ->
                    DiscoveredPeerCard(
                        peer = peer,
                        enabled = senderState is SenderState.Idle || senderState is SenderState.Error,
                        onCastClick = {
                            if (peer.requiresPin) {
                                dialogPinInput = ""
                                peerRequiringPin = peer
                            } else {
                                onConnectPeer(peer, "")
                            }
                        }
                    )
                }
            }

            // Manual Direct IP Connection Card
            item {
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
                                imageVector = Icons.Rounded.Lan,
                                contentDescription = null,
                                tint = IndigoSignal,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = stringResource(R.string.cast_direct_ip_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = manualIp,
                                onValueChange = { manualIp = it },
                                label = { Text(stringResource(R.string.cast_direct_ip_hint)) },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1.5f)
                                    .testTag("direct_ip_input")
                            )
                            OutlinedTextField(
                                value = manualPin,
                                onValueChange = { if (it.length <= 6) manualPin = it.filter(Char::isDigit) },
                                label = { Text("PIN") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(0.8f)
                                    .testTag("direct_pin_input")
                            )
                        }
                        Button(
                            onClick = {
                                onConnectDirectIp(manualIp, StreamProtocol.DEFAULT_PORT, manualPin)
                            },
                            enabled = senderState is SenderState.Idle || senderState is SenderState.Error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("direct_connect_cast_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondary,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Rounded.Cast, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.cast_connect_cast))
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun ActiveCastStudioCard(
    state: SenderState.Casting,
    onTogglePrivacyPause: () -> Unit,
    onStopCasting: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                if (state.isPrivacyPaused) ElectricCyan.copy(alpha = 0.5f) else LiveEmerald.copy(alpha = 0.55f),
                RoundedCornerShape(22.dp)
            ),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SignalPulseDot(
                        color = if (state.isPrivacyPaused) ElectricCyan else LiveEmerald,
                        isActive = !state.isPrivacyPaused
                    )
                    Column {
                        Text(
                            text = if (state.isPrivacyPaused) "PRIVACY CURTAIN ON" else stringResource(R.string.cast_live_banner),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isPrivacyPaused) ElectricCyan else LiveEmerald
                        )
                        Text(
                            text = "Mirroring to ${state.targetName}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = ElectricCyan.copy(alpha = 0.16f)
                ) {
                    Text(
                        text = state.transportLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                TelemetryPill(label = "FPS", value = "${state.telemetry.fps}")
                TelemetryPill(
                    label = "RTT",
                    value = "${state.telemetry.latencyMs}ms",
                    accentColor = LiveEmerald
                )
                TelemetryPill(
                    label = "BITRATE",
                    value = "${state.telemetry.bitrateKbps}kbps",
                    accentColor = IndigoSignal
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onTogglePrivacyPause,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("toggle_privacy_pause_button")
                ) {
                    Icon(
                        imageVector = if (state.isPrivacyPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.isPrivacyPaused) {
                            stringResource(R.string.cast_resume_stream)
                        } else {
                            stringResource(R.string.cast_pause_privacy)
                        }
                    )
                }

                Button(
                    onClick = onStopCasting,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("stop_casting_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BroadcastCoral,
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.cast_stop_casting))
                }
            }
        }
    }
}

@Composable
private fun DiscoveredPeerCard(
    peer: DiscoveredTvPeer,
    enabled: Boolean,
    onCastClick: () -> Unit
) {
    val isP2p = peer.transport == PeerTransport.WIFI_DIRECT
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                RoundedCornerShape(18.dp)
            )
            .clickable(enabled = enabled, onClick = onCastClick)
            .testTag("peer_card_${peer.id}"),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isP2p) ElectricCyan.copy(alpha = 0.16f) else IndigoSignal.copy(alpha = 0.16f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isP2p) Icons.Rounded.WifiTethering else Icons.Rounded.Tv,
                        contentDescription = null,
                        tint = if (isP2p) ElectricCyan else IndigoSignal
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = peer.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (peer.requiresPin) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = "PIN Protected",
                            tint = ElectricCyan,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "${peer.transport.label} • ${peer.hostAddress}:${peer.port}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = onCastClick,
                enabled = enabled,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("cast_to_peer_${peer.id}"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricCyan,
                    contentColor = Color(0xFF002A33)
                )
            ) {
                Text("Cast", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun EmptyPeersCard(isScanning: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                RoundedCornerShape(20.dp)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                modifier = Modifier.size(56.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isScanning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            color = ElectricCyan,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = null,
                            tint = ElectricCyan,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
            Text(
                text = if (isScanning) "Searching for BeamScreen TVs…" else stringResource(R.string.cast_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            Text(
                text = stringResource(R.string.cast_empty_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
