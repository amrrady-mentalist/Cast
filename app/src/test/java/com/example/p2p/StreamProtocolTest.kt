package com.example.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class StreamProtocolTest {

    @Test
    fun helloHandshakeRoundTripPreservesFields() {
        val bytes = StreamProtocol.encodeHello(
            senderName = "Pixel 9",
            pin = "4829",
            width = 1080,
            height = 1920,
            fps = 60
        )
        val decoded = StreamProtocol.decodeHello(bytes)
        assertEquals("Pixel 9", decoded.senderName)
        assertEquals("4829", decoded.pin)
        assertEquals(1080, decoded.width)
        assertEquals(1920, decoded.height)
        assertEquals(60, decoded.fps)
    }

    @Test
    fun helloAckRoundTripPreservesAcceptanceAndReason() {
        val rejected = StreamProtocol.decodeHelloAck(
            StreamProtocol.encodeHelloAck(
                accepted = false,
                tvName = "Living Room TV",
                reason = "Invalid pairing PIN"
            )
        )
        assertFalse(rejected.accepted)
        assertEquals("Living Room TV", rejected.tvName)
        assertEquals("Invalid pairing PIN", rejected.reason)
    }

    @Test
    fun packetFramingPreservesTypeTimestampAndPayload() {
        val outBytes = ByteArrayOutputStream()
        val dataOut = DataOutputStream(outBytes)
        val sampleNal = byteArrayOf(0, 0, 0, 1, 101, 42, 99)
        val framePayload = StreamProtocol.encodeVideoFrame(isKeyFrame = true, nalBytes = sampleNal)

        StreamProtocol.writePacket(dataOut, StreamProtocol.TYPE_VIDEO_FRAME, 123456789L, framePayload)
        StreamProtocol.writePacket(dataOut, StreamProtocol.TYPE_STOP, 0L, ByteArray(0))

        val dataIn = DataInputStream(ByteArrayInputStream(outBytes.toByteArray()))
        val p1 = StreamProtocol.readPacket(dataIn)!!
        assertEquals(StreamProtocol.TYPE_VIDEO_FRAME, p1.type)
        assertEquals(123456789L, p1.timestampUs)
        assertTrue((p1.payload[0].toInt() and StreamProtocol.FLAG_KEY_FRAME.toInt()) != 0)
        assertArrayEquals(sampleNal, p1.payload.copyOfRange(1, p1.payload.size))

        val p2 = StreamProtocol.readPacket(dataIn)!!
        assertEquals(StreamProtocol.TYPE_STOP, p2.type)
        assertEquals(0, p2.payload.size)
    }
}
