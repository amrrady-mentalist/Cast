package com.example.receiver

import com.example.ui.components.QrCodeGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZeroInstallReceiverTest {

    @Test
    fun webSocketAcceptKeyMatchesRfc6455Vector() {
        // RFC 6455 Section 1.3 standard test vector
        val accept = WebCastServer.computeWebSocketAcceptKey("dGhlIHNhbXBsZSBub25jZQ==")
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept)
    }

    @Test
    fun annexBNalSplitterExtractsSpsPpsAndIdr() {
        val sample = byteArrayOf(
            0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x1E, // SPS (nal_type = 7)
            0x00, 0x00, 0x01, 0x68, 0xCE.toByte(), 0x3C, 0x80.toByte(), // PPS (nal_type = 8)
            0x00, 0x00, 0x00, 0x01, 0x65, 0x88.toByte(), 0x84.toByte() // IDR (nal_type = 5)
        )
        val nals = Mpeg2TsH264Demuxer.splitAnnexBNals(sample)
        assertEquals(3, nals.size)
        assertEquals(7, nals[0][4].toInt() and 0x1F)
        assertEquals(8, nals[1][3].toInt() and 0x1F)
        assertEquals(5, nals[2][4].toInt() and 0x1F)
    }

    @Test
    fun rtpHeaderLengthHandlesExtensionAndCsrc() {
        val rtpWithCsrc = byteArrayOf(
            0x82.toByte(), 0x21, 0x00, 0x01,
            0, 0, 0, 0,
            0, 0, 0, 1,
            0, 0, 0, 2,
            0, 0, 0, 3,
            0x47, 0x40, 0x00, 0x10
        )
        val hdrLen = Mpeg2TsH264Demuxer.parseRtpHeaderLength(rtpWithCsrc, rtpWithCsrc.size)
        assertEquals(20, hdrLen)
        assertEquals(0x47, rtpWithCsrc[hdrLen].toInt())
    }

    @Test
    fun qrCodeGeneratorProducesValidBitmap() {
        val bmp = QrCodeGenerator.generateQrBitmap("http://192.168.49.1:8080", 256)
        assertNotNull(bmp)
        assertTrue(bmp!!.width == 256 && bmp.height == 256)
    }
}
