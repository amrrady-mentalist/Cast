package com.example.p2p

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.Socket
import java.nio.ByteBuffer

object StreamProtocol {
    const val DEFAULT_PORT = 8988
    const val NSD_SERVICE_TYPE = "_beamscreen._tcp."
    const val P2P_SERVICE_INSTANCE = "BeamScreenTV"
    const val P2P_SERVICE_TYPE = "_beamscreen._tcp"
    const val PROTOCOL_MAGIC = 0x42535431 // "BST1"

    const val TYPE_HELLO: Byte = 1
    const val TYPE_HELLO_ACK: Byte = 2
    const val TYPE_VIDEO_CONFIG: Byte = 3
    const val TYPE_VIDEO_FRAME: Byte = 4
    const val TYPE_PING: Byte = 5
    const val TYPE_PONG: Byte = 6
    const val TYPE_PAUSE_CURTAIN: Byte = 7
    const val TYPE_STOP: Byte = 8

    const val FLAG_KEY_FRAME: Byte = 1
    const val MAX_PACKET_BYTES = 16 * 1024 * 1024 // 16 MB safety guard

    data class Packet(
        val type: Byte,
        val timestampUs: Long,
        val payload: ByteArray
    )

    fun configureLowLatencySocket(socket: Socket) {
        runCatching {
            socket.tcpNoDelay = true
            socket.trafficClass = 0x10 // IPTOS_LOWDELAY
            socket.sendBufferSize = 512 * 1024
            socket.receiveBufferSize = 512 * 1024
            socket.keepAlive = true
        }
    }

    @Synchronized
    fun writePacket(
        out: DataOutputStream,
        type: Byte,
        timestampUs: Long,
        payload: ByteArray
    ) {
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.writeLong(timestampUs)
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    fun readPacket(input: DataInputStream): Packet? {
        val type = try {
            input.readByte()
        } catch (_: EOFException) {
            return null
        }
        val length = input.readInt()
        if (length < 0 || length > MAX_PACKET_BYTES) {
            throw IllegalStateException("Invalid packet length: $length")
        }
        val timestampUs = input.readLong()
        val payload = ByteArray(length)
        if (length > 0) {
            input.readFully(payload)
        }
        return Packet(type = type, timestampUs = timestampUs, payload = payload)
    }

    fun encodeHello(senderName: String, pin: String, width: Int, height: Int, fps: Int): ByteArray {
        val nameBytes = senderName.toByteArray(Charsets.UTF_8).take(64).toByteArray()
        val pinBytes = pin.toByteArray(Charsets.UTF_8).take(16).toByteArray()
        val buf = ByteBuffer.allocate(4 + 4 + 4 + 4 + 2 + nameBytes.size + 2 + pinBytes.size)
        buf.putInt(PROTOCOL_MAGIC)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(fps)
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putShort(pinBytes.size.toShort())
        buf.put(pinBytes)
        return buf.array()
    }

    data class HelloPayload(
        val width: Int,
        val height: Int,
        val fps: Int,
        val senderName: String,
        val pin: String
    )

    fun decodeHello(payload: ByteArray): HelloPayload {
        val buf = ByteBuffer.wrap(payload)
        val magic = buf.int
        require(magic == PROTOCOL_MAGIC) { "Unsupported protocol magic" }
        val width = buf.int
        val height = buf.int
        val fps = buf.int
        val nameLen = buf.short.toInt().coerceIn(0, 64)
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val pinLen = buf.short.toInt().coerceIn(0, 16)
        val pinBytes = ByteArray(pinLen)
        buf.get(pinBytes)
        return HelloPayload(
            width = width,
            height = height,
            fps = fps,
            senderName = String(nameBytes, Charsets.UTF_8).ifBlank { "Remote Phone" },
            pin = String(pinBytes, Charsets.UTF_8)
        )
    }

    fun encodeHelloAck(accepted: Boolean, tvName: String, reason: String = ""): ByteArray {
        val nameBytes = tvName.toByteArray(Charsets.UTF_8).take(64).toByteArray()
        val reasonBytes = reason.toByteArray(Charsets.UTF_8).take(128).toByteArray()
        val buf = ByteBuffer.allocate(1 + 2 + nameBytes.size + 2 + reasonBytes.size)
        buf.put(if (accepted) 1.toByte() else 0.toByte())
        buf.putShort(nameBytes.size.toShort())
        buf.put(nameBytes)
        buf.putShort(reasonBytes.size.toShort())
        buf.put(reasonBytes)
        return buf.array()
    }

    data class HelloAckPayload(
        val accepted: Boolean,
        val tvName: String,
        val reason: String
    )

    fun decodeHelloAck(payload: ByteArray): HelloAckPayload {
        val buf = ByteBuffer.wrap(payload)
        val accepted = buf.get() == 1.toByte()
        val nameLen = buf.short.toInt().coerceIn(0, 64)
        val nameBytes = ByteArray(nameLen)
        buf.get(nameBytes)
        val reasonLen = buf.short.toInt().coerceIn(0, 128)
        val reasonBytes = ByteArray(reasonLen)
        buf.get(reasonBytes)
        return HelloAckPayload(
            accepted = accepted,
            tvName = String(nameBytes, Charsets.UTF_8),
            reason = String(reasonBytes, Charsets.UTF_8)
        )
    }

    fun encodeVideoConfig(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + 4 + 4 + csd0.size + 4 + csd1.size)
        buf.putInt(width)
        buf.putInt(height)
        buf.putInt(csd0.size)
        buf.put(csd0)
        buf.putInt(csd1.size)
        buf.put(csd1)
        return buf.array()
    }

    data class VideoConfigPayload(
        val width: Int,
        val height: Int,
        val csd0: ByteArray,
        val csd1: ByteArray
    )

    fun decodeVideoConfig(payload: ByteArray): VideoConfigPayload {
        val buf = ByteBuffer.wrap(payload)
        val width = buf.int
        val height = buf.int
        val csd0Len = buf.int.coerceIn(0, 1024 * 64)
        val csd0 = ByteArray(csd0Len)
        if (csd0Len > 0) buf.get(csd0)
        val csd1Len = buf.int.coerceIn(0, 1024 * 64)
        val csd1 = ByteArray(csd1Len)
        if (csd1Len > 0) buf.get(csd1)
        return VideoConfigPayload(width, height, csd0, csd1)
    }

    fun encodeVideoFrame(isKeyFrame: Boolean, nalBytes: ByteArray): ByteArray {
        val out = ByteArray(1 + nalBytes.size)
        out[0] = if (isKeyFrame) FLAG_KEY_FRAME else 0
        System.arraycopy(nalBytes, 0, out, 1, nalBytes.size)
        return out
    }
}
