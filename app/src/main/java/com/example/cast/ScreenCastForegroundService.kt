package com.example.cast

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.data.QualityPreset
import com.example.data.SenderState
import com.example.data.StreamTelemetry
import com.example.p2p.StreamProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

class ScreenCastForegroundService : Service() {

    companion object {
        private const val TAG = "ScreenCastService"
        private const val CHANNEL_ID = "beamscreen_cast_channel"
        private const val NOTIFICATION_ID = 4021

        const val ACTION_START_CAST = "com.example.cast.ACTION_START_CAST"
        const val ACTION_STOP_CAST = "com.example.cast.ACTION_STOP_CAST"
        const val ACTION_TOGGLE_PRIVACY = "com.example.cast.ACTION_TOGGLE_PRIVACY"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_TARGET_NAME = "extra_target_name"
        const val EXTRA_SENDER_NAME = "extra_sender_name"
        const val EXTRA_PIN = "extra_pin"
        const val EXTRA_PRESET_ID = "extra_preset_id"
        const val EXTRA_LOW_LATENCY = "extra_low_latency"
        const val EXTRA_TRANSPORT_LABEL = "extra_transport_label"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var socket: Socket? = null
    private var dataOut: DataOutputStream? = null
    private var streamJob: Job? = null
    private var pingJob: Job? = null

    @Volatile
    private var isPrivacyPaused: Boolean = false

    @Volatile
    private var lastLatencyMs: Long = 12L

    @Volatile
    private var currentTargetName: String = "TV Screen"

    @Volatile
    private var currentTransportLabel: String = "P2P"

    @Volatile
    private var currentPreset: QualityPreset = QualityPreset.BALANCED_720P_60

    @Volatile
    private var lowLatencyMode: Boolean = true

    @Volatile
    private var currentWidth: Int = 720

    @Volatile
    private var currentHeight: Int = 1280

    @Volatile
    private var currentDensityDpi: Int = DisplayMetrics.DENSITY_HIGH

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Log.d(TAG, "MediaProjection stopped by system")
            stopCastingAndSelf()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val (encW, encH) = computeAlignedResolution(width, height, currentPreset.maxLongEdge)
            if (encW != currentWidth || encH != currentHeight) {
                serviceScope.launch(Dispatchers.Main) {
                    reconfigureEncoderAndDisplay(encW, encH)
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_CAST -> {
                stopCastingAndSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PRIVACY -> {
                togglePrivacyPause()
                return START_STICKY
            }
            ACTION_START_CAST -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                val host = intent.getStringExtra(EXTRA_HOST).orEmpty()
                val port = intent.getIntExtra(EXTRA_PORT, StreamProtocol.DEFAULT_PORT)
                currentTargetName = intent.getStringExtra(EXTRA_TARGET_NAME) ?: "TV Screen"
                val senderName = intent.getStringExtra(EXTRA_SENDER_NAME) ?: Build.MODEL
                val pin = intent.getStringExtra(EXTRA_PIN).orEmpty()
                currentPreset = QualityPreset.fromId(intent.getStringExtra(EXTRA_PRESET_ID))
                lowLatencyMode = intent.getBooleanExtra(EXTRA_LOW_LATENCY, true)
                currentTransportLabel = intent.getStringExtra(EXTRA_TRANSPORT_LABEL) ?: "P2P"

                if (resultCode != Activity.RESULT_OK || resultData == null || host.isBlank()) {
                    CastSessionBus.updateState(SenderState.Error("Screen capture permission was not granted"))
                    stopSelf()
                    return START_NOT_STICKY
                }

                // Mandatory Android 14+ foreground promotion BEFORE getMediaProjection
                val notification = buildNotification(currentTargetName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceCompat.startForeground(
                        this,
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }

                startCastSession(
                    resultCode = resultCode,
                    resultData = resultData,
                    host = host,
                    port = port,
                    senderName = senderName,
                    pin = pin
                )
            }
        }
        return START_STICKY
    }

    private fun startCastSession(
        resultCode: Int,
        resultData: Intent,
        host: String,
        port: Int,
        senderName: String,
        pin: String
    ) {
        streamJob?.cancel()
        pingJob?.cancel()
        isPrivacyPaused = false

        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = try {
            projectionManager.getMediaProjection(resultCode, resultData)
        } catch (err: Exception) {
            CastSessionBus.updateState(SenderState.Error(err.message ?: "Failed to initialize MediaProjection"))
            stopSelf()
            return
        }

        if (projection == null) {
            CastSessionBus.updateState(SenderState.Error("MediaProjection returned null"))
            stopSelf()
            return
        }

        mediaProjection = projection
        projection.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))

        val (rawW, rawH, dpi) = queryDisplayBounds()
        currentDensityDpi = dpi
        val (encW, encH) = computeAlignedResolution(rawW, rawH, currentPreset.maxLongEdge)
        currentWidth = encW
        currentHeight = encH

        CastSessionBus.updateState(SenderState.Connecting(currentTargetName))

        streamJob = serviceScope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 8000)
                StreamProtocol.configureLowLatencySocket(sock)
                socket = sock

                val out = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 256 * 1024))
                dataOut = out
                val input = DataInputStream(sock.getInputStream())

                StreamProtocol.writePacket(
                    out,
                    StreamProtocol.TYPE_HELLO,
                    0L,
                    StreamProtocol.encodeHello(
                        senderName = senderName,
                        pin = pin,
                        width = encW,
                        height = encH,
                        fps = currentPreset.fps
                    )
                )

                val ackPacket = StreamProtocol.readPacket(input)
                    ?: throw IllegalStateException("Receiver closed connection during handshake")
                if (ackPacket.type != StreamProtocol.TYPE_HELLO_ACK) {
                    throw IllegalStateException("Unexpected handshake response")
                }
                val ack = StreamProtocol.decodeHelloAck(ackPacket.payload)
                if (!ack.accepted) {
                    throw IllegalStateException(ack.reason.ifBlank { "Connection rejected by TV screen" })
                }
                if (ack.tvName.isNotBlank()) {
                    currentTargetName = ack.tvName
                }

                // Start inbound reader for PONG latency responses
                pingJob = launch {
                    runPingPongLoop(sock, input, out)
                }

                // Setup H.264 hardware encoder & VirtualDisplay
                setupEncoderAndVirtualDisplay(encW, encH, dpi, projection)

                CastSessionBus.updateState(
                    SenderState.Casting(
                        targetName = currentTargetName,
                        transportLabel = currentTransportLabel,
                        isPrivacyPaused = false,
                        telemetry = StreamTelemetry(
                            fps = currentPreset.fps,
                            bitrateKbps = currentPreset.bitrateBps / 1000,
                            width = encW,
                            height = encH,
                            latencyMs = lastLatencyMs,
                            transportLabel = currentTransportLabel
                        )
                    )
                )

                pumpEncodedFrames(out)
            } catch (err: Exception) {
                Log.e(TAG, "Cast session error", err)
                CastSessionBus.updateState(
                    SenderState.Error(err.message ?: "Lost connection to $currentTargetName")
                )
            } finally {
                cleanupResources()
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun runPingPongLoop(
        sock: Socket,
        input: DataInputStream,
        out: DataOutputStream
    ) {
        val pingSender = serviceScope.launch {
            while (isActive && !sock.isClosed) {
                delay(1500L)
                runCatching {
                    val sentAtNs = System.nanoTime()
                    val payload = ByteBuffer.allocate(8).putLong(lastLatencyMs).array()
                    StreamProtocol.writePacket(out, StreamProtocol.TYPE_PING, sentAtNs, payload)
                }
            }
        }
        try {
            while (!sock.isClosed) {
                val pkt = StreamProtocol.readPacket(input) ?: break
                if (pkt.type == StreamProtocol.TYPE_PONG) {
                    val rttMs = ((System.nanoTime() - pkt.timestampUs) / 1_000_000L).coerceIn(1L, 1500L)
                    lastLatencyMs = max(1L, rttMs / 2L)
                }
            }
        } catch (_: Exception) {
        } finally {
            pingSender.cancel()
        }
    }

    private fun setupEncoderAndVirtualDisplay(
        width: Int,
        height: Int,
        dpi: Int,
        projection: MediaProjection
    ) {
        releaseEncoderAndDisplay()

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, currentPreset.bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, currentPreset.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (lowLatencyMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
        }

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()

        encoder = codec
        inputSurface = surface

        virtualDisplay = projection.createVirtualDisplay(
            "BeamScreenMirror",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
    }

    private fun reconfigureEncoderAndDisplay(newW: Int, newH: Int) {
        val projection = mediaProjection ?: return
        currentWidth = newW
        currentHeight = newH
        runCatching {
            setupEncoderAndVirtualDisplay(newW, newH, currentDensityDpi, projection)
        }
    }

    private fun pumpEncodedFrames(out: DataOutputStream) {
        val bufferInfo = MediaCodec.BufferInfo()
        var totalFrames = 0L
        var windowFrames = 0
        var windowBytes = 0L
        var windowStartNs = System.nanoTime()

        while (socket?.isClosed == false && mediaProjection != null) {
            val codec = encoder ?: break
            val outIndex = try {
                codec.dequeueOutputBuffer(bufferInfo, 15_000L)
            } catch (_: Exception) {
                break
            }

            if (outIndex >= 0) {
                val encodedBuffer = codec.getOutputBuffer(outIndex)
                if (encodedBuffer != null && bufferInfo.size > 0) {
                    encodedBuffer.position(bufferInfo.offset)
                    encodedBuffer.limit(bufferInfo.offset + bufferInfo.size)
                    val bytes = ByteArray(bufferInfo.size)
                    encodedBuffer.get(bytes)

                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

                    if (isConfig) {
                        val cfgPayload = StreamProtocol.encodeVideoConfig(
                            width = currentWidth,
                            height = currentHeight,
                            csd0 = bytes,
                            csd1 = ByteArray(0)
                        )
                        StreamProtocol.writePacket(out, StreamProtocol.TYPE_VIDEO_CONFIG, 0L, cfgPayload)
                    } else if (!isPrivacyPaused) {
                        val framePayload = StreamProtocol.encodeVideoFrame(isKey, bytes)
                        StreamProtocol.writePacket(
                            out,
                            StreamProtocol.TYPE_VIDEO_FRAME,
                            bufferInfo.presentationTimeUs,
                            framePayload
                        )

                        totalFrames++
                        windowFrames++
                        windowBytes += bytes.size

                        val nowNs = System.nanoTime()
                        val elapsedNs = nowNs - windowStartNs
                        if (elapsedNs >= 1_000_000_000L) {
                            val sec = elapsedNs / 1_000_000_000.0
                            val fps = (windowFrames / sec).toInt()
                            val kbps = ((windowBytes * 8.0) / sec / 1000.0).toInt()
                            windowFrames = 0
                            windowBytes = 0L
                            windowStartNs = nowNs

                            CastSessionBus.updateState(
                                SenderState.Casting(
                                    targetName = currentTargetName,
                                    transportLabel = currentTransportLabel,
                                    isPrivacyPaused = isPrivacyPaused,
                                    telemetry = StreamTelemetry(
                                        fps = fps,
                                        bitrateKbps = kbps,
                                        width = currentWidth,
                                        height = currentHeight,
                                        latencyMs = lastLatencyMs,
                                        frameCount = totalFrames,
                                        transportLabel = currentTransportLabel
                                    )
                                )
                            )
                        }
                    }
                }
                codec.releaseOutputBuffer(outIndex, false)
            }
        }
    }

    private fun togglePrivacyPause() {
        isPrivacyPaused = !isPrivacyPaused
        serviceScope.launch {
            dataOut?.let { out ->
                runCatching {
                    StreamProtocol.writePacket(
                        out,
                        StreamProtocol.TYPE_PAUSE_CURTAIN,
                        0L,
                        byteArrayOf(if (isPrivacyPaused) 1 else 0)
                    )
                }
            }
            val current = CastSessionBus.senderState.value
            if (current is SenderState.Casting) {
                CastSessionBus.updateState(current.copy(isPrivacyPaused = isPrivacyPaused))
            }
        }
    }

    private fun queryDisplayBounds(): Triple<Int, Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Triple(bounds.width().coerceAtLeast(720), bounds.height().coerceAtLeast(1280), density)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels.coerceAtLeast(720), metrics.heightPixels.coerceAtLeast(1280), metrics.densityDpi)
        }
    }

    private fun computeAlignedResolution(srcW: Int, srcH: Int, maxLongEdge: Int): Pair<Int, Int> {
        val longEdge = max(srcW, srcH).toFloat()
        val scale = if (longEdge > maxLongEdge) maxLongEdge / longEdge else 1f
        val w = (((srcW * scale) / 16f).roundToInt() * 16).coerceAtLeast(320)
        val h = (((srcH * scale) / 16f).roundToInt() * 16).coerceAtLeast(320)
        return w to h
    }

    private fun stopCastingAndSelf() {
        streamJob?.cancel()
        pingJob?.cancel()
        serviceScope.launch {
            dataOut?.let { out ->
                runCatching { StreamProtocol.writePacket(out, StreamProtocol.TYPE_STOP, 0L, ByteArray(0)) }
            }
            cleanupResources()
            CastSessionBus.updateState(SenderState.Idle)
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun releaseEncoderAndDisplay() {
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        encoder?.let { codec ->
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        encoder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
    }

    private fun cleanupResources() {
        releaseEncoderAndDisplay()
        mediaProjection?.let { proj ->
            runCatching { proj.unregisterCallback(projectionCallback) }
            runCatching { proj.stop() }
        }
        mediaProjection = null
        runCatching { socket?.close() }
        socket = null
        dataOut = null
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(targetName: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ScreenCastForegroundService::class.java).apply {
                action = ACTION_STOP_CAST
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notif_casting_title))
            .setContentText(getString(R.string.notif_casting_text, targetName))
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop_action), stopIntent)
            .build()
    }

    override fun onDestroy() {
        cleanupResources()
        serviceScope.cancel()
        super.onDestroy()
    }
}
