package com.example.data

enum class QualityPreset(
    val id: String,
    val label: String,
    val subtitle: String,
    val maxLongEdge: Int,
    val fps: Int,
    val bitrateBps: Int
) {
    ULTRA_1080P_60(
        id = "1080p60",
        label = "1080p • 60 FPS",
        subtitle = "Crisp Full HD at 8 Mbps for fast P2P links",
        maxLongEdge = 1920,
        fps = 60,
        bitrateBps = 8_000_000
    ),
    BALANCED_720P_60(
        id = "720p60",
        label = "720p • 60 FPS",
        subtitle = "Ultra-low latency 5 Mbps smooth gaming & video",
        maxLongEdge = 1280,
        fps = 60,
        bitrateBps = 5_000_000
    ),
    LOW_DATA_720P_30(
        id = "720p30",
        label = "720p • 30 FPS",
        subtitle = "2.5 Mbps battery & long-range stability",
        maxLongEdge = 1280,
        fps = 30,
        bitrateBps = 2_500_000
    );

    companion object {
        fun fromId(id: String?): QualityPreset =
            entries.firstOrNull { it.id == id } ?: BALANCED_720P_60
    }
}

enum class AspectScaleMode(val label: String) {
    FIT("Fit"),
    FILL("Fill")
}

enum class TvOrientationMode(val label: String) {
    AUTO("Auto"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait")
}

enum class PeerTransport(val label: String) {
    WIFI_DIRECT("Wi-Fi Direct"),
    LOCAL_LAN("Local Wi-Fi"),
    DIRECT_IP("Direct IP")
}

data class DiscoveredTvPeer(
    val id: String,
    val name: String,
    val hostAddress: String,
    val port: Int,
    val p2pDeviceAddress: String? = null,
    val transport: PeerTransport,
    val requiresPin: Boolean = false
)

data class WifiDirectGroupInfo(
    val isGroupOwner: Boolean = false,
    val networkSsid: String = "",
    val passphrase: String = "",
    val groupOwnerIp: String = "192.168.49.1"
)

data class StreamTelemetry(
    val fps: Int = 0,
    val bitrateKbps: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val latencyMs: Long = 0,
    val frameCount: Long = 0,
    val transportLabel: String = "P2P"
)

data class BeamSettings(
    val tvDisplayName: String,
    val qualityPreset: QualityPreset,
    val requirePin: Boolean,
    val pairingPin: String,
    val keepScreenAwake: Boolean,
    val lowLatencyMode: Boolean,
    val autoStartReceiver: Boolean
)

sealed class ReceiverState {
    data object Idle : ReceiverState()
    data object Starting : ReceiverState()
    data class Standby(
        val localIpAddresses: List<String>,
        val listenPort: Int,
        val webPortalPort: Int,
        val wfdSinkPort: Int,
        val wfdAdvertised: Boolean,
        val wifiDirectGroup: WifiDirectGroupInfo?,
        val nsdRegisteredName: String?
    ) : ReceiverState()
    data class Connecting(val senderName: String) : ReceiverState()
    data class Streaming(
        val senderName: String,
        val videoWidth: Int,
        val videoHeight: Int,
        val isPrivacyPaused: Boolean,
        val telemetry: StreamTelemetry,
        val webFrame: androidx.compose.ui.graphics.ImageBitmap? = null
    ) : ReceiverState()
    data class Error(val message: String) : ReceiverState()
}

sealed class SenderState {
    data object Idle : SenderState()
    data class Connecting(val targetName: String) : SenderState()
    data class AwaitingConsent(val targetName: String) : SenderState()
    data class Casting(
        val targetName: String,
        val transportLabel: String,
        val isPrivacyPaused: Boolean,
        val telemetry: StreamTelemetry
    ) : SenderState()
    data class Error(val message: String) : SenderState()
}
