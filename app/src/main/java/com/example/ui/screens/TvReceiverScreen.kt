package com.example.ui.screens

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.R
import com.example.data.AspectScaleMode
import com.example.data.BeamSettings
import com.example.data.ReceiverState
import com.example.data.TvOrientationMode
import com.example.p2p.StreamProtocol
import com.example.ui.components.InfoMetricCard
import com.example.ui.components.PermissionRequestBanner
import com.example.ui.components.QrCodeGenerator
import com.example.ui.components.SignalPulseDot
import com.example.ui.components.TelemetryPill
import com.example.ui.theme.BroadcastCoral
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.IndigoSignal
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LiveEmerald
import kotlinx.coroutines.delay

@Composable
fun TvReceiverScreen(
    receiverState: ReceiverState,
    settings: BeamSettings,
    hasDiscoveryPermission: Boolean,
    aspectMode: AspectScaleMode,
    orientationMode: TvOrientationMode,
    onRequestDiscoveryPermission: () -> Unit,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onAttachSurface: (android.view.Surface?) -> Unit,
    onToggleAspectMode: () -> Unit,
    onCycleOrientation: () -> Unit,
    onDisconnectSender: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (receiverState) {
        is ReceiverState.Connecting,
        is ReceiverState.Streaming -> {
            FullScreenTvPlayer(
                receiverState = receiverState,
                aspectMode = aspectMode,
                orientationMode = orientationMode,
                onAttachSurface = onAttachSurface,
                onToggleAspectMode = onToggleAspectMode,
                onCycleOrientation = onCycleOrientation,
                onDisconnectSender = onDisconnectSender,
                modifier = modifier
            )
        }
        else -> {
            TvStandbyDashboard(
                receiverState = receiverState,
                settings = settings,
                hasDiscoveryPermission = hasDiscoveryPermission,
                onRequestDiscoveryPermission = onRequestDiscoveryPermission,
                onStartReceiver = onStartReceiver,
                onStopReceiver = onStopReceiver,
                onRefreshNetwork = onRefreshNetwork,
                modifier = modifier
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TvStandbyDashboard(
    receiverState: ReceiverState,
    settings: BeamSettings,
    hasDiscoveryPermission: Boolean,
    onRequestDiscoveryPermission: () -> Unit,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit,
    onRefreshNetwork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isListening = receiverState is ReceiverState.Standby || receiverState is ReceiverState.Starting
    val standby = receiverState as? ReceiverState.Standby
    val clipboard = LocalClipboardManager.current
    var copiedLabel by remember { mutableStateOf<String?>(null) }
    var showLargeQr by remember { mutableStateOf(false) }

    LaunchedEffect(copiedLabel) {
        if (copiedLabel != null) {
            delay(2000)
            copiedLabel = null
        }
    }

    val primaryIp = standby?.localIpAddresses?.firstOrNull()
        ?: standby?.wifiDirectGroup?.groupOwnerIp
    val webPortalUrl = if (standby != null && !primaryIp.isNullOrBlank()) {
        "https://$primaryIp:${standby.webHttpsPort}"
    } else {
        null
    }
    val qrBitmap = remember(webPortalUrl) {
        webPortalUrl?.let { QrCodeGenerator.generateQrBitmap(it, 512) }
    }

    if (showLargeQr && qrBitmap != null && webPortalUrl != null) {
        AlertDialog(
            onDismissRequest = { showLargeQr = false },
            title = { Text("Scan to Cast Without App") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = Color.White,
                        modifier = Modifier.padding(4.dp)
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Zero-install Web Cast QR Code",
                            modifier = Modifier
                                .size(240.dp)
                                .padding(12.dp)
                        )
                    }
                    Text(
                        text = webPortalUrl,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold
                        ),
                        color = ElectricCyan,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Open on any phone connected to the same Wi-Fi or this TV's Wi-Fi Direct group.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLargeQr = false }) {
                    Text("Close")
                }
            }
        )
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
            // Top Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Tv,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(10.dp)
                                .size(22.dp)
                        )
                    }
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = stringResource(R.string.tv_mode_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = if (isListening) LiveEmerald.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isListening) LiveEmerald.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outline
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SignalPulseDot(
                            color = if (isListening) LiveEmerald else MaterialTheme.colorScheme.onSurfaceVariant,
                            isActive = isListening
                        )
                        Text(
                            text = if (isListening) "ON AIR" else "PAUSED",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isListening) LiveEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!hasDiscoveryPermission) {
                PermissionRequestBanner(onRequestPermission = onRequestDiscoveryPermission)
            }

            // Hero Cinema TV Standby Card
            TvHeroStandbyCard(
                tvName = settings.tvDisplayName,
                isListening = isListening,
                isStarting = receiverState is ReceiverState.Starting,
                onStartReceiver = onStartReceiver,
                onStopReceiver = onStopReceiver
            )

            // Zero-Install Casting (Browser / Laptop / Any Phone)
            if (standby != null) {
                ZeroInstallCastCard(
                    webPortalUrl = webPortalUrl,
                    httpFallbackPort = standby.webPortalPort,
                    qrBitmap = qrBitmap,
                    wfdAdvertised = standby.wfdAdvertised,
                    copiedUrl = copiedLabel == "url",
                    onCopyUrl = {
                        webPortalUrl?.let {
                            clipboard.setText(AnnotatedString(it))
                            copiedLabel = "url"
                        }
                    },
                    onRefreshNetwork = onRefreshNetwork,
                    onEnlargeQr = { showLargeQr = true }
                )

                val displayIp = primaryIp ?: "Waiting for Wi-Fi…"

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val isWide = maxWidth >= 520.dp
                    if (isWide) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            EndpointIpCard(
                                ip = displayIp,
                                port = standby.listenPort,
                                copied = copiedLabel == "ip",
                                onCopy = {
                                    clipboard.setText(AnnotatedString(displayIp))
                                    copiedLabel = "ip"
                                },
                                modifier = Modifier.weight(1f)
                            )
                            WifiDirectCard(
                                groupSsid = standby.wifiDirectGroup?.networkSsid.orEmpty(),
                                groupPass = standby.wifiDirectGroup?.passphrase.orEmpty(),
                                isGroupActive = standby.wifiDirectGroup?.isGroupOwner == true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            EndpointIpCard(
                                ip = displayIp,
                                port = standby.listenPort,
                                copied = copiedLabel == "ip",
                                onCopy = {
                                    clipboard.setText(AnnotatedString(displayIp))
                                    copiedLabel = "ip"
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            WifiDirectCard(
                                groupSsid = standby.wifiDirectGroup?.networkSsid.orEmpty(),
                                groupPass = standby.wifiDirectGroup?.passphrase.orEmpty(),
                                isGroupActive = standby.wifiDirectGroup?.isGroupOwner == true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Pairing Security & Capabilities Row
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    InfoMetricCard(
                        icon = if (settings.requirePin) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                        label = stringResource(R.string.tv_pin_label),
                        value = if (settings.requirePin) settings.pairingPin else "Open Access",
                        subValue = if (settings.requirePin) "Sender must enter PIN" else "Instant 1-tap pairing",
                        accentColor = if (settings.requirePin) ElectricCyan else LiveEmerald,
                        modifier = Modifier.weight(1f, fill = true).widthIn(min = 160.dp)
                    )
                    InfoMetricCard(
                        icon = Icons.Rounded.CastConnected,
                        label = "RECEIVER PROTOCOLS",
                        value = "WFD • Web • P2P",
                        subValue = "Miracast RTSP, Browser WS & H.264",
                        accentColor = IndigoSignal,
                        modifier = Modifier.weight(1f, fill = true).widthIn(min = 160.dp)
                    )
                }
            }

            if (receiverState is ReceiverState.Error) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        text = receiverState.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ZeroInstallCastCard(
    webPortalUrl: String?,
    httpFallbackPort: Int,
    qrBitmap: android.graphics.Bitmap?,
    wfdAdvertised: Boolean,
    copiedUrl: Boolean,
    onCopyUrl: () -> Unit,
    onRefreshNetwork: () -> Unit,
    onEnlargeQr: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                1.dp,
                ElectricCyan.copy(alpha = 0.42f),
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
                    Icon(
                        imageVector = Icons.Rounded.QrCode2,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "BROWSER CAST (NO APP NEEDED)",
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (wfdAdvertised) LiveEmerald.copy(alpha = 0.16f) else ElectricCyan.copy(alpha = 0.16f)
                    ) {
                        Text(
                            text = if (wfdAdvertised) "Miracast + HTTPS" else "HTTPS Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (wfdAdvertised) LiveEmerald else ElectricCyan,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = onRefreshNetwork,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("refresh_ip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = "Refresh IP addresses",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (qrBitmap != null) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color.White,
                        modifier = Modifier
                            .size(104.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onEnlargeQr)
                            .testTag("qr_code_thumbnail")
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Scan QR to cast from browser",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(6.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Open in Laptop or Phone Browser",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Laptops (Chrome/Edge/Safari): cast entire screen, window, or tab. Tap Advanced → Proceed on the local TLS warning. Also reachable via HTTP :$httpFallbackPort.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (webPortalUrl != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = webPortalUrl,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontFamily = JetBrainsMonoFontFamily,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = ElectricCyan,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = onCopyUrl,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("copy_web_url_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy Web Cast URL",
                                    tint = if (copiedUrl) LiveEmerald else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Connect both devices to the same Wi-Fi (or turn on this phone's Hotspot) and tap Refresh.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvHeroStandbyCard(
    tvName: String,
    isListening: Boolean,
    isStarting: Boolean,
    onStartReceiver: () -> Unit,
    onStopReceiver: () -> Unit
) {
    val infinite = rememberInfiniteTransition(label = "radarWave")
    val ring1 by infinite.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Restart),
        label = "ring1"
    )
    val ring2 by infinite.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, delayMillis = 1200), RepeatMode.Restart),
        label = "ring2"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (isListening) ElectricCyan.copy(alpha = 0.38f) else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(26.dp)
            ),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(225.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_tv_standby),
                contentDescription = "TV Standby Ambient Backdrop",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xCC070A12),
                                Color(0x99070A12),
                                Color(0xEE070A12)
                            )
                        )
                    )
            )

            if (isListening) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width * 0.84f, size.height * 0.28f)
                    val maxR = size.minDimension * 0.42f
                    drawCircle(
                        color = ElectricCyan.copy(alpha = (1f - ring1) * 0.35f),
                        radius = maxR * ring1,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawCircle(
                        color = ElectricCyan.copy(alpha = (1f - ring2) * 0.35f),
                        radius = maxR * ring2,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = tvName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = if (isListening) {
                            "Ready for Miracast Wireless Display, QR Browser Cast, or BeamScreen P2P."
                        } else {
                            "TV receiver is paused. Start the receiver to become discoverable."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFCBD5E1)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isListening) {
                        OutlinedButton(
                            onClick = onStopReceiver,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("stop_tv_receiver_button"),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.tv_stop_receiver))
                        }
                    } else {
                        Button(
                            onClick = onStartReceiver,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("start_tv_receiver_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ElectricCyan,
                                contentColor = Color(0xFF002A33)
                            )
                        ) {
                            if (isStarting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF002A33)
                                )
                            } else {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.tv_start_receiver))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EndpointIpCard(
    ip: String,
    port: Int,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.border(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
            RoundedCornerShape(18.dp)
        ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Lan,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.tv_network_endpoint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "$ip:$port",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (copied) "Copied IP address" else "Direct socket port $port",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (copied) LiveEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("copy_ip_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy Receiver IP",
                    tint = if (copied) LiveEmerald else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun WifiDirectCard(
    groupSsid: String,
    groupPass: String,
    isGroupActive: Boolean,
    modifier: Modifier = Modifier
) {
    InfoMetricCard(
        icon = Icons.Rounded.WifiTethering,
        label = stringResource(R.string.tv_p2p_ssid),
        value = if (isGroupActive && groupSsid.isNotBlank()) groupSsid else "P2P Ready",
        subValue = if (isGroupActive && groupPass.isNotBlank()) {
            "Pass: $groupPass • 192.168.49.1:${StreamProtocol.DEFAULT_PORT}"
        } else {
            "Peer-to-peer group ready (no router needed)"
        },
        accentColor = if (isGroupActive) LiveEmerald else ElectricCyan,
        modifier = modifier
    )
}

@Composable
private fun FullScreenTvPlayer(
    receiverState: ReceiverState,
    aspectMode: AspectScaleMode,
    orientationMode: TvOrientationMode,
    onAttachSurface: (android.view.Surface?) -> Unit,
    onToggleAspectMode: () -> Unit,
    onCycleOrientation: () -> Unit,
    onDisconnectSender: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hudVisible by remember { mutableStateOf(true) }

    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(4500L)
            hudVisible = false
        }
    }

    BackHandler {
        onDisconnectSender()
    }

    val streaming = receiverState as? ReceiverState.Streaming
    val connecting = receiverState as? ReceiverState.Connecting
    val senderName = streaming?.senderName ?: connecting?.senderName ?: "Remote Phone"
    val videoW = streaming?.videoWidth ?: 1080
    val videoH = streaming?.videoHeight ?: 1920
    val webFrame = streaming?.webFrame

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { hudVisible = !hudVisible }
    ) {
        val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val videoAspect = (videoW.toFloat() / videoH.toFloat()).coerceIn(0.25f, 4f)
        val containerAspect = containerW / containerH

        val surfaceModifier = if (aspectMode == AspectScaleMode.FILL) {
            Modifier.fillMaxSize()
        } else {
            val (targetW, targetH) = if (videoAspect > containerAspect) {
                maxWidth to (maxWidth / videoAspect)
            } else {
                (maxHeight * videoAspect) to maxHeight
            }
            Modifier
                .size(width = targetW, height = targetH)
                .align(Alignment.Center)
        }

        if (webFrame != null) {
            Image(
                bitmap = webFrame,
                contentDescription = "Live Zero-Install Web Stream",
                contentScale = if (aspectMode == AspectScaleMode.FILL) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            DisposableEffect(Unit) {
                onDispose { onAttachSurface(null) }
            }

            AndroidView(
                factory = { ctx ->
                    SurfaceView(ctx).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                onAttachSurface(holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) {
                                onAttachSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                onAttachSurface(null)
                            }
                        })
                    }
                },
                modifier = surfaceModifier
            )
        }

        // Connecting Spinner before first frame
        if (streaming == null || streaming.telemetry.frameCount == 0L) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CircularProgressIndicator(color = ElectricCyan)
                Text(
                    text = "Linking stream from $senderName…",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Sender Privacy Curtain Overlay
        if (streaming?.isPrivacyPaused == true) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xE6070A12)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VisibilityOff,
                        contentDescription = null,
                        tint = ElectricCyan,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Screen Paused by $senderName",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White
                    )
                    Text(
                        text = "Connection stays live — video will resume shortly.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        // Auto-hiding Cinema TV HUD Overlay
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(240)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Telemetry Bar
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xCC000000), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                            SignalPulseDot(color = LiveEmerald, isActive = true)
                            Text(
                                text = stringResource(R.string.tv_connected_from, senderName),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                        if (streaming != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = ElectricCyan.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = streaming.telemetry.transportLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = ElectricCyan,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (streaming != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            TelemetryPill(
                                label = "FPS",
                                value = "${streaming.telemetry.fps}"
                            )
                            TelemetryPill(
                                label = "LATENCY",
                                value = "${streaming.telemetry.latencyMs}ms",
                                accentColor = LiveEmerald
                            )
                            TelemetryPill(
                                label = "RES",
                                value = "${streaming.videoWidth}x${streaming.videoHeight}",
                                accentColor = IndigoSignal
                            )
                        }
                    }
                }

                // Bottom Control Dock
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 20.dp),
                    shape = RoundedCornerShape(28.dp),
                    color = Color(0xD9101624),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = onToggleAspectMode,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("tv_aspect_toggle_button"),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) {
                            Icon(Icons.Rounded.AspectRatio, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(aspectMode.label)
                        }

                        FilledTonalButton(
                            onClick = onCycleOrientation,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("tv_orientation_toggle_button"),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) {
                            Icon(Icons.Rounded.ScreenRotation, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(orientationMode.label)
                        }

                        Button(
                            onClick = onDisconnectSender,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("tv_disconnect_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BroadcastCoral,
                                contentColor = Color.White
                            ),
                            contentPadding = PaddingValues(horizontal = 16.dp)
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.tv_disconnect_stream))
                        }
                    }
                }
            }
        }
    }
}
