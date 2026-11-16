package com.example.receiver

import android.util.Log
import com.example.data.StreamTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Wi-Fi Display (Miracast) RTSP Sink + RTP/MPEG-2 TS H.264 Demuxer.
 * Allows Miracast-capable phones (Samsung Smart View, Xiaomi Wireless Display, etc.)
 * and Windows PCs (Win+K) to cast directly without installing any sender app.
 */
class MiracastWfdServer(
    private val decoder: VideoDecoderRenderer,
    private val onSessionConnecting: (senderName: String) -> Unit,
    private val onSessionStreaming: (senderName: String, width: Int, height: Int, telemetry: StreamTelemetry) -> Unit,
    private val onSessionEnded: () -> Unit
) {
    companion object {
        private const val TAG = "MiracastWfdServer"
        const val DEFAULT_RTSP_PORT = 7236
        const val DEFAULT_RTP_PORT = 1028
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var rtspServerSocket: ServerSocket? = null
    private var activeRtspClient: Socket? = null
    private var rtpSocket: DatagramSocket? = null
    private var serverJob: Job? = null
    private var rtpJob: Job? = null

    @Volatile
    var listeningRtspPort: Int = DEFAULT_RTSP_PORT
        private set

    fun start(preferredPort: Int = DEFAULT_RTSP_PORT): Int {
        if (serverJob?.isActive == true && rtspServerSocket != null) {
            return listeningRtspPort
        }

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
        rtspServerSocket = srv
        listeningRtspPort = srv.localPort

        serverJob = scope.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                handleRtspSession(client)
            }
        }
        return listeningRtspPort
    }

    private fun handleRtspSession(client: Socket) {
        activeRtspClient = client
        val senderIp = client.inetAddress?.hostAddress ?: "Miracast Device"
        onSessionConnecting("Miracast ($senderIp)")

        try {
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val out = client.getOutputStream()

            val localRtpPort = openRtpSocket()
            var sessionId = "BEAMWFD01"
            var videoWidth = 1280
            var videoHeight = 720
            var m1Sent = false

            // WFD Sink initiates M1 OPTIONS or responds if source initiates
            // Give source a brief moment; if it acts as RTSP server/client we handle requests cleanly
            client.soTimeout = 1500
            while (!client.isClosed) {
                val request = try {
                    readRtspMessage(reader)
                } catch (_: java.net.SocketTimeoutException) {
                    if (!m1Sent) {
                        m1Sent = true
                        sendRtspRequest(
                            out,
                            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nRequire: org.wfa.wfd1.0\r\n\r\n"
                        )
                    }
                    continue
                } ?: break

                client.soTimeout = 0
                val firstLine = request.firstLine
                if (firstLine.startsWith("RTSP/1.0")) {
                    // Response to our M1 OPTIONS; ignore and continue listening
                    continue
                }

                val cseq = request.headers["cseq"] ?: "1"
                val method = firstLine.substringBefore(" ").uppercase()

                when (method) {
                    "OPTIONS" -> {
                        val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cseq\r\n" +
                            "Public: org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER\r\n\r\n"
                        sendRtspResponse(out, resp)
                    }

                    "GET_PARAMETER" -> {
                        val body = request.body
                        val respBody = buildString {
                            if (body.contains("wfd_video_formats")) {
                                // Standard WFD CEA 720p60 / 1080p30 H.264 Constrained Baseline/High
                                append("wfd_video_formats: 40 00 02 02 0001DEFF 157C7FFF 00000FFF 00 0000 0000 00 none none\r\n")
                            }
                            if (body.contains("wfd_audio_codecs")) {
                                append("wfd_audio_codecs: LPCM 00000003 00, AAC 0000000F 00\r\n")
                            }
                            if (body.contains("wfd_client_rtp_ports")) {
                                append("wfd_client_rtp_ports: RTP/AVP/UDP;unicast $localRtpPort 0 mode=play\r\n")
                            }
                            if (body.contains("wfd_content_protection")) {
                                append("wfd_content_protection: none\r\n")
                            }
                            if (body.contains("wfd_coupled_sink")) {
                                append("wfd_coupled_sink: none\r\n")
                            }
                            if (body.contains("wfd_uibc_capability")) {
                                append("wfd_uibc_capability: none\r\n")
                            }
                            if (body.contains("wfd_standby_resume_capability")) {
                                append("wfd_standby_resume_capability: supported\r\n")
                            }
                        }
                        val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cseq\r\n" +
                            "Content-Type: text/parameters\r\n" +
                            "Content-Length: ${respBody.toByteArray().size}\r\n\r\n" +
                            respBody
                        sendRtspResponse(out, resp)
                    }

                    "SET_PARAMETER" -> {
                        val body = request.body
                        if (body.contains("wfd_trigger_method: SETUP")) {
                            // Acknowledge M5 trigger, then send M6 SETUP
                            sendRtspResponse(out, "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n")
                            val setupReq = "SETUP rtsp://$senderIp/wfd1.0/streamid=0 RTSP/1.0\r\n" +
                                "CSeq: 100\r\n" +
                                "Transport: RTP/AVP/UDP;unicast;client_port=$localRtpPort\r\n\r\n"
                            sendRtspRequest(out, setupReq)
                        } else if (body.contains("wfd_trigger_method: TEARDOWN")) {
                            sendRtspResponse(out, "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n")
                            break
                        } else {
                            if (body.contains("1920")) {
                                videoWidth = 1920
                                videoHeight = 1080
                            }
                            sendRtspResponse(out, "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n")
                        }
                    }

                    "SETUP" -> {
                        val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cseq\r\n" +
                            "Session: $sessionId;timeout=60\r\n" +
                            "Transport: RTP/AVP/UDP;unicast;client_port=$localRtpPort;server_port=$localRtpPort\r\n\r\n"
                        sendRtspResponse(out, resp)
                    }

                    "PLAY" -> {
                        val resp = "RTSP/1.0 200 OK\r\n" +
                            "CSeq: $cseq\r\n" +
                            "Session: $sessionId\r\n\r\n"
                        sendRtspResponse(out, resp)
                        startRtpReceiverLoop("Miracast ($senderIp)", videoWidth, videoHeight)
                    }

                    "TEARDOWN" -> {
                        sendRtspResponse(out, "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n")
                        break
                    }

                    else -> {
                        sendRtspResponse(out, "RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n")
                    }
                }
            }
        } catch (err: Exception) {
            Log.d(TAG, "RTSP session closed: ${err.message}")
        } finally {
            stopRtpReceiver()
            runCatching { client.close() }
            if (activeRtspClient === client) {
                activeRtspClient = null
            }
            decoder.release()
            onSessionEnded()
        }
    }

    private fun openRtpSocket(): Int {
        stopRtpReceiver()
        val sock = try {
            DatagramSocket(DEFAULT_RTP_PORT)
        } catch (_: Exception) {
            DatagramSocket(0)
        }
        sock.receiveBufferSize = 1024 * 1024
        rtpSocket = sock
        return sock.localPort
    }

    private fun startRtpReceiverLoop(senderName: String, initialW: Int, initialH: Int) {
        rtpJob?.cancel()
        val sock = rtpSocket ?: return
        val demuxer = Mpeg2TsH264Demuxer(decoder, initialW, initialH)

        rtpJob = scope.launch {
            val buf = ByteArray(4096)
            val packet = DatagramPacket(buf, buf.size)
            var windowFrames = 0
            var windowBytes = 0L
            var windowStartNs = System.nanoTime()
            var totalFrames = 0L

            while (isActive && !sock.isClosed) {
                try {
                    sock.receive(packet)
                } catch (_: Exception) {
                    break
                }
                val len = packet.length
                if (len <= 12) continue

                val rtpHeaderLen = Mpeg2TsH264Demuxer.parseRtpHeaderLength(buf, len)
                if (rtpHeaderLen < 0 || rtpHeaderLen >= len) continue

                val decodedCount = demuxer.pushTsBytes(buf, rtpHeaderLen, len - rtpHeaderLen)
                if (decodedCount > 0) {
                    totalFrames += decodedCount
                    windowFrames += decodedCount
                }
                windowBytes += (len - rtpHeaderLen)

                val nowNs = System.nanoTime()
                val elapsedNs = nowNs - windowStartNs
                if (elapsedNs >= 1_000_000_000L || totalFrames == 1L) {
                    val sec = ((elapsedNs.coerceAtLeast(1_000_000L)) / 1_000_000_000.0)
                    val fps = if (elapsedNs >= 1_000_000_000L) (windowFrames / sec).toInt() else 30
                    val kbps = if (elapsedNs >= 1_000_000_000L) ((windowBytes * 8.0) / sec / 1000.0).toInt() else 0
                    if (elapsedNs >= 1_000_000_000L) {
                        windowFrames = 0
                        windowBytes = 0L
                        windowStartNs = nowNs
                    }
                    onSessionStreaming(
                        senderName,
                        demuxer.videoWidth,
                        demuxer.videoHeight,
                        StreamTelemetry(
                            fps = fps,
                            bitrateKbps = kbps,
                            width = demuxer.videoWidth,
                            height = demuxer.videoHeight,
                            latencyMs = 24L,
                            frameCount = totalFrames,
                            transportLabel = "Miracast WFD"
                        )
                    )
                }
            }
        }
    }

    fun disconnectActiveSession() {
        runCatching { activeRtspClient?.close() }
        activeRtspClient = null
        stopRtpReceiver()
    }

    private fun stopRtpReceiver() {
        rtpJob?.cancel()
        rtpJob = null
        runCatching { rtpSocket?.close() }
        rtpSocket = null
    }

    fun stop() {
        serverJob?.cancel()
        serverJob = null
        disconnectActiveSession()
        runCatching { rtspServerSocket?.close() }
        rtspServerSocket = null
    }

    private data class RtspMessage(
        val firstLine: String,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRtspMessage(reader: BufferedReader): RtspMessage? {
        var firstLine: String?
        do {
            firstLine = reader.readLine() ?: return null
        } while (firstLine.isBlank())

        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(chars, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            String(chars, 0, read)
        } else {
            ""
        }
        return RtspMessage(firstLine, headers, body)
    }

    private fun sendRtspResponse(out: OutputStream, payload: String) {
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun sendRtspRequest(out: OutputStream, payload: String) {
        out.write(payload.toByteArray(Charsets.UTF_8))
        out.flush()
    }
}

/**
 * Pure Kotlin RTP + MPEG-2 TS -> H.264 Annex-B NAL demuxer for Miracast WFD streams.
 */
internal class Mpeg2TsH264Demuxer(
    private val decoder: VideoDecoderRenderer?,
    var videoWidth: Int = 1280,
    var videoHeight: Int = 720
) {
    private val pesBuffer = ByteArrayOutputStream(256 * 1024)
    private var videoPid: Int = -1
    private var decoderConfigured = false
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    private var ptsCounterUs = 0L

    fun pushTsBytes(data: ByteArray, offset: Int, length: Int): Int {
        var framesDecoded = 0
        var pos = offset
        val end = offset + length
        while (pos + 188 <= end) {
            if (data[pos].toInt() != 0x47) {
                pos++
                continue
            }
            val b1 = data[pos + 1].toInt() and 0xFF
            val b2 = data[pos + 2].toInt() and 0xFF
            val b3 = data[pos + 3].toInt() and 0xFF
            val pusi = (b1 and 0x40) != 0
            val pid = ((b1 and 0x1F) shl 8) or b2
            val adaptControl = (b3 shr 4) and 0x03

            var payloadStart = pos + 4
            if (adaptControl == 2 || adaptControl == 3) {
                val adaptLen = data[payloadStart].toInt() and 0xFF
                payloadStart += 1 + adaptLen
            }
            if ((adaptControl == 1 || adaptControl == 3) && payloadStart < pos + 188) {
                if (pid != 0 && (videoPid == -1 || pid == videoPid)) {
                    if (pusi && payloadStart + 9 <= pos + 188 &&
                        data[payloadStart].toInt() == 0x00 &&
                        data[payloadStart + 1].toInt() == 0x00 &&
                        data[payloadStart + 2].toInt() == 0x01
                    ) {
                        val streamId = data[payloadStart + 3].toInt() and 0xFF
                        if (streamId in 0xE0..0xEF) {
                            videoPid = pid
                            if (pesBuffer.size() > 0) {
                                framesDecoded += flushAccessUnit(pesBuffer.toByteArray())
                                pesBuffer.reset()
                            }
                            val pesHeaderLen = data[payloadStart + 8].toInt() and 0xFF
                            val esStart = payloadStart + 9 + pesHeaderLen
                            if (esStart < pos + 188) {
                                pesBuffer.write(data, esStart, pos + 188 - esStart)
                            }
                        }
                    } else if (pid == videoPid) {
                        pesBuffer.write(data, payloadStart, pos + 188 - payloadStart)
                    }
                }
            }
            pos += 188
        }
        return framesDecoded
    }

    internal fun flushAccessUnit(auBytes: ByteArray): Int {
        val nals = splitAnnexBNals(auBytes)
        if (nals.isEmpty()) return 0
        var decoded = 0
        for (nal in nals) {
            if (nal.size < 5) continue
            val scLen = if (nal[2].toInt() == 0x01) 3 else 4
            val nalType = nal[scLen].toInt() and 0x1F
            when (nalType) {
                7 -> { // SPS
                    lastSps = nal
                    tryConfigureDecoder()
                }
                8 -> { // PPS
                    lastPps = nal
                    tryConfigureDecoder()
                }
                5, 1 -> { // IDR or non-IDR slice
                    if (decoderConfigured) {
                        ptsCounterUs += 16_666L
                        decoder?.decodeFrame(nal, ptsCounterUs, nalType == 5)
                        decoded++
                    }
                }
            }
        }
        return decoded
    }

    private fun tryConfigureDecoder() {
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (!decoderConfigured) {
            decoder?.configure(videoWidth, videoHeight, sps, pps)
            decoderConfigured = true
        }
    }

    companion object {
        fun parseRtpHeaderLength(buf: ByteArray, packetLen: Int): Int {
            if (packetLen < 12) return -1
            val b0 = buf[0].toInt() and 0xFF
            val hasExtension = (b0 and 0x10) != 0
            val csrcCount = b0 and 0x0F
            var offset = 12 + csrcCount * 4
            if (hasExtension) {
                if (offset + 4 > packetLen) return -1
                val extWords = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
                offset += 4 + extWords * 4
            }
            return offset
        }

        fun splitAnnexBNals(data: ByteArray): List<ByteArray> {
            val starts = mutableListOf<Int>()
            var i = 0
            while (i + 3 < data.size) {
                if (data[i].toInt() == 0x00 && data[i + 1].toInt() == 0x00) {
                    if (data[i + 2].toInt() == 0x01) {
                        starts.add(i)
                        i += 3
                        continue
                    } else if (i + 4 < data.size && data[i + 2].toInt() == 0x00 && data[i + 3].toInt() == 0x01) {
                        starts.add(i)
                        i += 4
                        continue
                    }
                }
                i++
            }
            if (starts.isEmpty()) return emptyList()
            val nals = ArrayList<ByteArray>(starts.size)
            for (idx in starts.indices) {
                val start = starts[idx]
                val end = if (idx + 1 < starts.size) starts[idx + 1] else data.size
                if (end > start) {
                    nals.add(data.copyOfRange(start, end))
                }
            }
            return nals
        }
    }
}
