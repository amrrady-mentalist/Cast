package com.example.receiver

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.example.data.StreamTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Embedded HTTP + RFC 6455 WebSocket Server allowing any phone or laptop browser
 * to cast its screen (where getDisplayMedia is supported), camera, photos, or videos
 * to this phone TV with ZERO app installation.
 */
class WebCastServer(
    private val onWebConnecting: (senderName: String) -> Unit,
    private val onWebFrame: (senderName: String, frame: ImageBitmap, width: Int, height: Int, telemetry: StreamTelemetry) -> Unit,
    private val onWebPaused: (senderName: String, paused: Boolean) -> Unit,
    private val onWebEnded: () -> Unit
) {
    companion object {
        private const val TAG = "WebCastServer"
        const val DEFAULT_HTTP_PORT = 8080
        private const val WS_MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        private const val MSG_CONTROL: Byte = 0x01
        private const val MSG_IMAGE_FRAME: Byte = 0x02

        fun computeWebSocketAcceptKey(secKey: String): String {
            val sha1 = MessageDigest.getInstance("SHA-1")
            val digest = sha1.digest((secKey.trim() + WS_MAGIC_GUID).toByteArray(Charsets.UTF_8))
            return Base64.encodeToString(digest, Base64.NO_WRAP)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var serverSocket: ServerSocket? = null
    private var activeWsClient: Socket? = null
    private var acceptJob: Job? = null

    @Volatile
    var listeningPort: Int = DEFAULT_HTTP_PORT
        private set

    @Volatile
    private var tvName: String = "BeamScreen TV"

    @Volatile
    private var requiredPin: String? = null

    fun updateConfig(tvName: String, requiredPin: String?) {
        this.tvName = tvName
        this.requiredPin = requiredPin
    }

    fun start(tvName: String, requiredPin: String?, preferredPort: Int = DEFAULT_HTTP_PORT): Int {
        this.tvName = tvName
        this.requiredPin = requiredPin
        if (acceptJob?.isActive == true && serverSocket != null) {
            return listeningPort
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
        serverSocket = srv
        listeningPort = srv.localPort

        acceptJob = scope.launch {
            while (isActive && !srv.isClosed) {
                val client = try {
                    srv.accept()
                } catch (_: Exception) {
                    break
                }
                launch { handleHttpOrWsConnection(client) }
            }
        }
        return listeningPort
    }

    private fun handleHttpOrWsConnection(client: Socket) {
        try {
            client.tcpNoDelay = true
            val input = BufferedInputStream(client.getInputStream(), 128 * 1024)
            val out = client.getOutputStream()

            val headerBytes = readHttpHeaderBytes(input) ?: run {
                client.close()
                return
            }
            val headerText = String(headerBytes, Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty()
            val path = requestLine.split(" ").getOrNull(1).orEmpty()

            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
            }

            if (path.startsWith("/ws") && headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                val wsKey = headers["sec-websocket-key"] ?: run {
                    client.close()
                    return
                }
                val accept = computeWebSocketAcceptKey(wsKey)
                val handshake = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                out.write(handshake.toByteArray(Charsets.UTF_8))
                out.flush()
                handleWebSocketLoop(client, DataInputStream(input), out)
            } else {
                val html = buildWebPortalHtml(tvName, !requiredPin.isNullOrBlank())
                val body = html.toByteArray(Charsets.UTF_8)
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
                out.write(response.toByteArray(Charsets.UTF_8))
                out.write(body)
                out.flush()
                client.close()
            }
        } catch (err: Exception) {
            Log.d(TAG, "HTTP/WS connection closed: ${err.message}")
            runCatching { client.close() }
        }
    }

    private fun handleWebSocketLoop(client: Socket, input: DataInputStream, out: OutputStream) {
        activeWsClient?.let { runCatching { it.close() } }
        activeWsClient = client

        val remoteIp = client.inetAddress?.hostAddress ?: "Browser"
        var senderLabel = "Web ($remoteIp)"
        var pinVerified = requiredPin.isNullOrBlank()
        var totalFrames = 0L
        var windowFrames = 0
        var windowBytes = 0L
        var windowStartNs = System.nanoTime()
        var measuredFps = 0
        var measuredKbps = 0

        onWebConnecting(senderLabel)

        try {
            while (!client.isClosed) {
                val frame = readWebSocketFrame(input) ?: break
                when (frame.opcode) {
                    0x08 -> break // Close
                    0x09 -> sendWsPong(out, frame.payload)
                    0x01 -> { // Text control message
                        val msg = String(frame.payload, Charsets.UTF_8)
                        when {
                            msg.startsWith("HELLO:") -> {
                                val parts = msg.removePrefix("HELLO:").split("|")
                                senderLabel = parts.getOrNull(0)?.take(28)?.ifBlank { null } ?: senderLabel
                                val suppliedPin = parts.getOrNull(1).orEmpty()
                                val expected = requiredPin
                                if (!expected.isNullOrBlank() && suppliedPin != expected) {
                                    sendWsText(out, "PIN_REJECTED")
                                    break
                                }
                                pinVerified = true
                                sendWsText(out, "READY")
                                onWebConnecting(senderLabel)
                            }
                            msg == "PAUSE" -> onWebPaused(senderLabel, true)
                            msg == "RESUME" -> onWebPaused(senderLabel, false)
                            msg == "STOP" -> break
                        }
                    }
                    0x02 -> { // Binary packet: [1B type][payload]
                        if (!pinVerified || frame.payload.size <= 1) continue
                        val msgType = frame.payload[0]
                        if (msgType == MSG_IMAGE_FRAME) {
                            val imgBytes = frame.payload.copyOfRange(1, frame.payload.size)
                            val bmp = BitmapFactory.decodeByteArray(imgBytes, 0, imgBytes.size) ?: continue
                            val imgBmp = bmp.asImageBitmap()
                            val w = bmp.width
                            val h = bmp.height

                            totalFrames++
                            windowFrames++
                            windowBytes += imgBytes.size

                            val nowNs = System.nanoTime()
                            val elapsedNs = nowNs - windowStartNs
                            if (elapsedNs >= 1_000_000_000L) {
                                val sec = elapsedNs / 1_000_000_000.0
                                measuredFps = (windowFrames / sec).toInt()
                                measuredKbps = ((windowBytes * 8.0) / sec / 1000.0).toInt()
                                windowFrames = 0
                                windowBytes = 0L
                                windowStartNs = nowNs
                            }

                            onWebFrame(
                                senderLabel,
                                imgBmp,
                                w,
                                h,
                                StreamTelemetry(
                                    fps = measuredFps,
                                    bitrateKbps = measuredKbps,
                                    width = w,
                                    height = h,
                                    latencyMs = 28L,
                                    frameCount = totalFrames,
                                    transportLabel = "Zero-Install Web"
                                )
                            )
                        } else if (msgType == MSG_CONTROL) {
                            val text = String(frame.payload, 1, frame.payload.size - 1, Charsets.UTF_8)
                            if (text == "STOP") break
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
            if (activeWsClient === client) {
                activeWsClient = null
            }
            onWebEnded()
        }
    }

    fun disconnectActiveSession() {
        runCatching { activeWsClient?.close() }
        activeWsClient = null
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        disconnectActiveSession()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun readHttpHeaderBytes(input: BufferedInputStream): ByteArray? {
        val out = ByteArray(16384)
        var pos = 0
        while (pos < out.size) {
            val b = input.read()
            if (b < 0) return if (pos == 0) null else out.copyOf(pos)
            out[pos++] = b.toByte()
            if (pos >= 4 &&
                out[pos - 4] == '\r'.code.toByte() &&
                out[pos - 3] == '\n'.code.toByte() &&
                out[pos - 2] == '\r'.code.toByte() &&
                out[pos - 1] == '\n'.code.toByte()
            ) {
                return out.copyOf(pos)
            }
        }
        return out
    }

    private data class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readWebSocketFrame(input: DataInputStream): WsFrame? {
        val b0 = try {
            input.readUnsignedByte()
        } catch (_: EOFException) {
            return null
        }
        val b1 = input.readUnsignedByte()
        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) {
            length = input.readUnsignedShort().toLong()
        } else if (length == 127L) {
            length = input.readLong()
        }
        if (length < 0 || length > 12 * 1024 * 1024L) {
            throw IllegalStateException("WS payload too large: $length")
        }
        val maskKey = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        if (length > 0) {
            input.readFully(payload)
            if (maskKey != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                }
            }
        }
        return WsFrame(opcode, payload)
    }

    private fun sendWsText(out: OutputStream, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        sendWsFrame(out, 0x01, bytes)
    }

    private fun sendWsPong(out: OutputStream, payload: ByteArray) {
        sendWsFrame(out, 0x0A, payload)
    }

    private fun sendWsFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        val header = when {
            payload.size < 126 -> byteArrayOf((0x80 or opcode).toByte(), payload.size.toByte())
            payload.size <= 65535 -> byteArrayOf(
                (0x80 or opcode).toByte(),
                126.toByte(),
                (payload.size shr 8).toByte(),
                payload.size.toByte()
            )
            else -> return
        }
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun buildWebPortalHtml(tvDisplayName: String, requiresPin: Boolean): String {
        val escapedName = tvDisplayName
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no" />
<title>Cast to $escapedName</title>
<style>
  :root { --bg:#070A12; --card:#141C2E; --cyan:#22D3EE; --coral:#FB7185; --green:#34D399; --text:#F1F5F9; --muted:#94A3B8; }
  * { box-sizing: border-box; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
  body { margin:0; padding:20px; background:var(--bg); color:var(--text); min-height:100vh; display:flex; justify-content:center; }
  .wrap { width:100%; max-width:480px; display:flex; flex-direction:column; gap:14px; }
  .card { background:var(--card); border:1px solid #28354E; border-radius:20px; padding:18px; }
  .badge { display:inline-flex; align-items:center; gap:6px; font-size:11px; font-weight:700; letter-spacing:.08em; color:var(--cyan); text-transform:uppercase; }
  h1 { margin:6px 0 4px; font-size:22px; }
  p { margin:0; color:var(--muted); font-size:13px; line-height:1.45; }
  input, select { width:100%; padding:12px 14px; border-radius:12px; border:1px solid #28354E; background:#0F1524; color:var(--text); font-size:15px; margin-top:8px; }
  .btn { width:100%; min-height:48px; border:0; border-radius:14px; font-size:15px; font-weight:700; cursor:pointer; display:flex; align-items:center; justify-content:center; gap:8px; margin-top:10px; }
  .btn-primary { background:var(--cyan); color:#002A33; }
  .btn-secondary { background:#182238; color:var(--text); border:1px solid #28354E; }
  .btn-stop { background:var(--coral); color:#fff; }
  .btn:disabled { opacity:0.45; cursor:not-allowed; }
  .status { margin-top:10px; padding:10px 12px; border-radius:12px; background:#0F1524; font-size:13px; color:var(--green); }
  .warn { color:#FBBF24; }
  video, canvas { display:none; }
  #previewImg { width:100%; max-height:200px; object-fit:contain; border-radius:12px; margin-top:10px; display:none; background:#000; }
</style>
</head>
<body>
<div class="wrap">
  <div class="card">
    <div class="badge">● Zero-Install Web Cast</div>
    <h1>$escapedName</h1>
    <p>Cast directly from your browser without installing any app.</p>
    <input id="deviceName" type="text" placeholder="Your Phone Name (e.g. Alex's Phone)" />
    ${if (requiresPin) """<input id="pinInput" type="tel" maxlength="6" placeholder="Enter 4-digit TV PIN" />""" else ""}
    <select id="fpsSelect">
      <option value="24">24 FPS • Balanced Low Latency</option>
      <option value="30" selected>30 FPS • Smooth Screen Motion</option>
      <option value="15">15 FPS • Battery Saver</option>
    </select>
    <div id="statusBox" class="status">Ready to connect</div>
  </div>

  <div class="card">
    <button id="btnScreen" class="btn btn-primary" onclick="startScreenCast()">Cast Browser / Screen</button>
    <button id="btnCamera" class="btn btn-secondary" onclick="startCameraCast()">Cast Live Camera</button>
    <label class="btn btn-secondary" style="margin-top:10px;">
      Beam Photo or Video File
      <input id="fileInput" type="file" accept="image/*,video/*" style="display:none" onchange="handleFileCast(event)" />
    </label>
    <button id="btnStop" class="btn btn-stop" onclick="stopCasting()" disabled>Stop Casting</button>
    <img id="previewImg" alt="Live Preview" />
    <p id="screenNote" class="warn" style="margin-top:10px; display:none;"></p>
  </div>

  <div class="card">
    <p><strong style="color:#F1F5F9;">Tip for Samsung / Xiaomi / Windows:</strong> You can also swipe down Quick Settings &rarr; <em>Smart View / Cast / Wireless Display</em> to mirror your entire phone screen natively with zero app.</p>
  </div>
</div>

<video id="srcVideo" autoplay playsinline muted></video>
<canvas id="frameCanvas"></canvas>

<script>
let ws = null;
let stream = null;
let timer = null;
let sending = false;

const statusBox = document.getElementById('statusBox');
const btnStop = document.getElementById('btnStop');
const video = document.getElementById('srcVideo');
const canvas = document.getElementById('frameCanvas');
const ctx = canvas.getContext('2d');
const previewImg = document.getElementById('previewImg');
const screenNote = document.getElementById('screenNote');

if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
  screenNote.style.display = 'block';
  screenNote.textContent = 'Note: Your mobile browser blocks full-screen capture from web pages. Use Live Camera, Photo/Video cast above, or your phone\u2019s built-in Smart View / Wireless Display.';
}

function setStatus(msg, isWarn) {
  statusBox.textContent = msg;
  statusBox.className = isWarn ? 'status warn' : 'status';
}

function connectWs(onReady) {
  if (ws && ws.readyState === WebSocket.OPEN) {
    onReady();
    return;
  }
  const proto = location.protocol === 'https:' ? 'wss://' : 'ws://';
  ws = new WebSocket(proto + location.host + '/ws');
  ws.binaryType = 'arraybuffer';
  ws.onopen = () => {
    const name = (document.getElementById('deviceName').value || navigator.platform || 'Web Phone').trim();
    const pinEl = document.getElementById('pinInput');
    const pin = pinEl ? pinEl.value.trim() : '';
    ws.send('HELLO:' + name + '|' + pin);
  };
  ws.onmessage = (ev) => {
    if (typeof ev.data === 'string') {
      if (ev.data === 'READY') {
        btnStop.disabled = false;
        onReady();
      } else if (ev.data === 'PIN_REJECTED') {
        setStatus('Incorrect TV PIN. Check PIN on TV screen.', true);
        stopCasting();
      }
    }
  };
  ws.onclose = () => {
    stopLoopOnly();
    btnStop.disabled = true;
    setStatus('Disconnected from TV', false);
  };
}

async function startScreenCast() {
  if (!navigator.mediaDevices || !navigator.mediaDevices.getDisplayMedia) {
    setStatus('This mobile browser does not allow web screen capture. Try Firefox/Desktop, Smart View, or Photo/Camera cast below.', true);
    return;
  }
  try {
    stream = await navigator.mediaDevices.getDisplayMedia({ video: { frameRate: 30 }, audio: false });
    connectWs(() => beginVideoStream(stream, 'Casting Screen Live'));
  } catch (e) {
    setStatus('Screen capture canceled: ' + e.message, true);
  }
}

async function startCameraCast() {
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment', width: { ideal: 1280 } }, audio: false });
    connectWs(() => beginVideoStream(stream, 'Casting Camera Live'));
  } catch (e) {
    setStatus('Camera permission denied: ' + e.message, true);
  }
}

function beginVideoStream(mediaStream, label) {
  video.srcObject = mediaStream;
  video.play();
  previewImg.style.display = 'none';
  setStatus(label, false);
  const fps = parseInt(document.getElementById('fpsSelect').value || '30', 10);
  const intervalMs = Math.max(30, Math.floor(1000 / fps));
  stream.getVideoTracks()[0].onended = () => stopCasting();

  clearInterval(timer);
  timer = setInterval(() => {
    if (!ws || ws.readyState !== WebSocket.OPEN || sending || video.videoWidth === 0) return;
    const maxEdge = 960;
    const scale = Math.min(1, maxEdge / Math.max(video.videoWidth, video.videoHeight));
    const w = Math.round(video.videoWidth * scale);
    const h = Math.round(video.videoHeight * scale);
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    ctx.drawImage(video, 0, 0, w, h);
    sending = true;
    canvas.toBlob((blob) => {
      if (!blob || !ws || ws.readyState !== WebSocket.OPEN) { sending = false; return; }
      blob.arrayBuffer().then((buf) => {
        const pkt = new Uint8Array(1 + buf.byteLength);
        pkt[0] = 0x02;
        pkt.set(new Uint8Array(buf), 1);
        ws.send(pkt.buffer);
        sending = false;
      }).catch(() => { sending = false; });
    }, 'image/jpeg', 0.72);
  }, intervalMs);
}

function handleFileCast(ev) {
  const file = ev.target.files[0];
  if (!file) return;
  stopLoopOnly();
  const url = URL.createObjectURL(file);
  if (file.type.startsWith('image/')) {
    const img = new Image();
    img.onload = () => {
      const maxEdge = 1280;
      const scale = Math.min(1, maxEdge / Math.max(img.width, img.height));
      canvas.width = Math.round(img.width * scale);
      canvas.height = Math.round(img.height * scale);
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      previewImg.src = url;
      previewImg.style.display = 'block';
      connectWs(() => {
        canvas.toBlob((blob) => {
          blob.arrayBuffer().then((buf) => {
            const pkt = new Uint8Array(1 + buf.byteLength);
            pkt[0] = 0x02;
            pkt.set(new Uint8Array(buf), 1);
            ws.send(pkt.buffer);
            setStatus('Beamed photo: ' + file.name, false);
          });
        }, 'image/jpeg', 0.85);
      });
    };
    img.src = url;
  } else if (file.type.startsWith('video/')) {
    video.srcObject = null;
    video.src = url;
    video.loop = true;
    video.muted = true;
    video.play();
    connectWs(() => beginVideoStream({ getVideoTracks: () => [{ onended: null }] }, 'Streaming video: ' + file.name));
  }
}

function stopLoopOnly() {
  clearInterval(timer);
  timer = null;
  sending = false;
  if (stream && stream.getTracks) {
    stream.getTracks().forEach(t => t.stop());
  }
  stream = null;
  video.pause();
  video.srcObject = null;
}

function stopCasting() {
  stopLoopOnly();
  if (ws && ws.readyState === WebSocket.OPEN) {
    try { ws.send('STOP'); } catch (_) {}
    ws.close();
  }
  ws = null;
  btnStop.disabled = true;
}
</script>
</body>
</html>
""".trimIndent()
    }
}
