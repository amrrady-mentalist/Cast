package com.example.receiver

import android.view.Surface
import com.example.data.ReceiverState
import com.example.data.StreamTelemetry
import com.example.data.WifiDirectGroupInfo
import com.example.p2p.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class TvReceiverEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val decoder = VideoDecoderRenderer()

    private val _receiverState = MutableStateFlow<ReceiverState>(ReceiverState.Idle)
    val receiverState: StateFlow<ReceiverState> = _receiverState.asStateFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var activeClientSocket: Socket? = null

    private var acceptJob: Job? = null

    @Volatile
    private var tvName: String = "BeamScreen TV"

    @Volatile
    private var requiredPin: String? = null

    @Volatile
    private var latestEndpoints: List<String> = emptyList()

    @Volatile
    private var latestP2pGroup: WifiDirectGroupInfo? = null

    @Volatile
    private var latestNsdName: String? = null

    @Volatile
    private var latestWfdAdvertised: Boolean = false

    @Volatile
    private var activePort: Int = StreamProtocol.DEFAULT_PORT

    @Volatile
    var webPortalPort: Int = WebCastServer.DEFAULT_HTTP_PORT
        private set

    @Volatile
    var wfdSinkPort: Int = MiracastWfdServer.DEFAULT_RTSP_PORT
        private set

    private val webCastServer = WebCastServer(
        onWebConnecting = { senderName ->
            _receiverState.value = ReceiverState.Connecting(senderName)
        },
        onWebFrame = { senderName, frame, width, height, telemetry ->
            _receiverState.value = ReceiverState.Streaming(
                senderName = senderName,
                videoWidth = width,
                videoHeight = height,
                isPrivacyPaused = false,
                telemetry = telemetry,
                webFrame = frame
            )
        },
        onWebPaused = { senderName, paused ->
            val current = _receiverState.value
            if (current is ReceiverState.Streaming) {
                _receiverState.value = current.copy(
                    senderName = senderName,
                    isPrivacyPaused = paused
                )
            }
        },
        onWebEnded = {
            if (serverSocket != null) {
                publishStandby()
            }
        }
    )

    private val miracastWfdServer = MiracastWfdServer(
        decoder = decoder,
        onSessionConnecting = { senderName ->
            _receiverState.value = ReceiverState.Connecting(senderName)
        },
        onSessionStreaming = { senderName, width, height, telemetry ->
            _receiverState.value = ReceiverState.Streaming(
                senderName = senderName,
                videoWidth = width,
                videoHeight = height,
                isPrivacyPaused = false,
                telemetry = telemetry,
                webFrame = null
            )
        },
        onSessionEnded = {
            if (serverSocket != null) {
                publishStandby()
            }
        }
    )

    fun attachVideoSurface(surface: Surface?) {
        decoder.attachSurface(surface)
    }

    fun updateStandbyMetadata(
        localIps: List<String>,
        p2pGroup: WifiDirectGroupInfo?,
        nsdName: String?,
        wfdAdvertised: Boolean
    ) {
        latestEndpoints = localIps
        latestP2pGroup = p2pGroup
        latestNsdName = nsdName
        latestWfdAdvertised = wfdAdvertised
        if (_receiverState.value is ReceiverState.Standby) {
            publishStandby()
        }
    }

    fun updateConfig(tvName: String, requiredPin: String?) {
        this.tvName = tvName
        this.requiredPin = requiredPin
        webCastServer.updateConfig(tvName, requiredPin)
    }

    private fun publishStandby() {
        _receiverState.value = ReceiverState.Standby(
            localIpAddresses = latestEndpoints,
            listenPort = activePort,
            webPortalPort = webPortalPort,
            wfdSinkPort = wfdSinkPort,
            wfdAdvertised = latestWfdAdvertised,
            wifiDirectGroup = latestP2pGroup,
            nsdRegisteredName = latestNsdName
        )
    }

    fun start(tvName: String, requiredPin: String?, preferredPort: Int = StreamProtocol.DEFAULT_PORT): Int {
        this.tvName = tvName
        this.requiredPin = requiredPin
        if (acceptJob?.isActive == true && serverSocket != null) {
            return activePort
        }

        _receiverState.value = ReceiverState.Starting
        val srv = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(preferredPort))
            }
        } catch (_: Exception) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(0))
            }
        }
        serverSocket = srv
        activePort = srv.localPort

        // Start Zero-Install Web Cast Portal & Miracast WFD Sink
        webPortalPort = webCastServer.start(tvName, requiredPin)
        wfdSinkPort = miracastWfdServer.start()

        publishStandby()

        acceptJob = scope.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                handleClientSession(client)
                if (isActive && !srv.isClosed) {
                    publishStandby()
                }
            }
        }
        return activePort
    }

    private fun handleClientSession(client: Socket) {
        activeClientSocket = client
        StreamProtocol.configureLowLatencySocket(client)

        try {
            val input = DataInputStream(BufferedInputStream(client.getInputStream(), 256 * 1024))
            val output = DataOutputStream(client.getOutputStream())

            val helloPacket = StreamProtocol.readPacket(input) ?: return
            if (helloPacket.type != StreamProtocol.TYPE_HELLO) return
            val hello = StreamProtocol.decodeHello(helloPacket.payload)

            val expectedPin = requiredPin
            if (!expectedPin.isNullOrBlank() && hello.pin != expectedPin) {
                StreamProtocol.writePacket(
                    output,
                    StreamProtocol.TYPE_HELLO_ACK,
                    0L,
                    StreamProtocol.encodeHelloAck(
                        accepted = false,
                        tvName = tvName,
                        reason = "Invalid pairing PIN"
                    )
                )
                return
            }

            StreamProtocol.writePacket(
                output,
                StreamProtocol.TYPE_HELLO_ACK,
                0L,
                StreamProtocol.encodeHelloAck(accepted = true, tvName = tvName)
            )

            _receiverState.value = ReceiverState.Connecting(hello.senderName)

            var videoW = hello.width.coerceAtLeast(360)
            var videoH = hello.height.coerceAtLeast(640)
            var isPaused = false
            var totalFrames = 0L
            var windowFrames = 0
            var windowBytes = 0L
            var windowStartNs = System.nanoTime()
            var measuredFps = 0
            var measuredKbps = 0
            var lastLatencyMs = 12L

            val transportLabel = if (client.inetAddress?.hostAddress?.startsWith("192.168.49.") == true) {
                "Wi-Fi Direct"
            } else {
                "P2P LAN"
            }

            while (!client.isClosed) {
                val packet = StreamProtocol.readPacket(input) ?: break
                when (packet.type) {
                    StreamProtocol.TYPE_VIDEO_CONFIG -> {
                        val cfg = StreamProtocol.decodeVideoConfig(packet.payload)
                        videoW = cfg.width
                        videoH = cfg.height
                        decoder.configure(cfg.width, cfg.height, cfg.csd0, cfg.csd1)
                        _receiverState.value = ReceiverState.Streaming(
                            senderName = hello.senderName,
                            videoWidth = videoW,
                            videoHeight = videoH,
                            isPrivacyPaused = isPaused,
                            telemetry = StreamTelemetry(
                                fps = measuredFps,
                                bitrateKbps = measuredKbps,
                                width = videoW,
                                height = videoH,
                                latencyMs = lastLatencyMs,
                                frameCount = totalFrames,
                                transportLabel = transportLabel
                            )
                        )
                    }

                    StreamProtocol.TYPE_VIDEO_FRAME -> {
                        if (packet.payload.size > 1) {
                            val isKeyFrame = (packet.payload[0].toInt() and StreamProtocol.FLAG_KEY_FRAME.toInt()) != 0
                            val nal = packet.payload.copyOfRange(1, packet.payload.size)
                            decoder.decodeFrame(nal, packet.timestampUs, isKeyFrame)

                            totalFrames++
                            windowFrames++
                            windowBytes += nal.size

                            val nowNs = System.nanoTime()
                            val elapsedNs = nowNs - windowStartNs
                            if (elapsedNs >= 1_000_000_000L) {
                                val sec = elapsedNs / 1_000_000_000.0
                                measuredFps = (windowFrames / sec).toInt()
                                measuredKbps = ((windowBytes * 8.0) / sec / 1000.0).toInt()
                                windowFrames = 0
                                windowBytes = 0L
                                windowStartNs = nowNs

                                _receiverState.value = ReceiverState.Streaming(
                                    senderName = hello.senderName,
                                    videoWidth = videoW,
                                    videoHeight = videoH,
                                    isPrivacyPaused = isPaused,
                                    telemetry = StreamTelemetry(
                                        fps = measuredFps,
                                        bitrateKbps = measuredKbps,
                                        width = videoW,
                                        height = videoH,
                                        latencyMs = lastLatencyMs,
                                        frameCount = totalFrames,
                                        transportLabel = transportLabel
                                    )
                                )
                            } else if (totalFrames == 1L) {
                                _receiverState.value = ReceiverState.Streaming(
                                    senderName = hello.senderName,
                                    videoWidth = videoW,
                                    videoHeight = videoH,
                                    isPrivacyPaused = isPaused,
                                    telemetry = StreamTelemetry(
                                        fps = measuredFps,
                                        bitrateKbps = measuredKbps,
                                        width = videoW,
                                        height = videoH,
                                        latencyMs = lastLatencyMs,
                                        frameCount = totalFrames,
                                        transportLabel = transportLabel
                                    )
                                )
                            }
                        }
                    }

                    StreamProtocol.TYPE_PING -> {
                        StreamProtocol.writePacket(output, StreamProtocol.TYPE_PONG, packet.timestampUs, packet.payload)
                        if (packet.payload.size >= 8) {
                            val reportedLatency = java.nio.ByteBuffer.wrap(packet.payload).long
                            if (reportedLatency in 1..2000) {
                                lastLatencyMs = reportedLatency
                            }
                        }
                    }

                    StreamProtocol.TYPE_PAUSE_CURTAIN -> {
                        isPaused = packet.payload.firstOrNull() == 1.toByte()
                        _receiverState.value = ReceiverState.Streaming(
                            senderName = hello.senderName,
                            videoWidth = videoW,
                            videoHeight = videoH,
                            isPrivacyPaused = isPaused,
                            telemetry = StreamTelemetry(
                                fps = measuredFps,
                                bitrateKbps = measuredKbps,
                                width = videoW,
                                height = videoH,
                                latencyMs = lastLatencyMs,
                                frameCount = totalFrames,
                                transportLabel = transportLabel
                            )
                        )
                    }

                    StreamProtocol.TYPE_STOP -> break
                }
            }
        } catch (_: Exception) {
            // Session closed or reset by remote peer
        } finally {
            decoder.release()
            runCatching { client.close() }
            if (activeClientSocket === client) {
                activeClientSocket = null
            }
        }
    }

    fun disconnectActiveSender() {
        runCatching { activeClientSocket?.close() }
        activeClientSocket = null
        webCastServer.disconnectActiveSession()
        miracastWfdServer.disconnectActiveSession()
        if (serverSocket != null) {
            publishStandby()
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        webCastServer.stop()
        miracastWfdServer.stop()
        disconnectActiveSender()
        runCatching { serverSocket?.close() }
        serverSocket = null
        decoder.release()
        _receiverState.value = ReceiverState.Idle
    }
}
