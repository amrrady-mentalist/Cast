package com.example.receiver

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class VideoDecoderRenderer {

    companion object {
        private const val TAG = "VideoDecoderRenderer"
    }

    private val lock = ReentrantLock()
    private var codec: MediaCodec? = null
    private var targetSurface: Surface? = null
    private var currentWidth: Int = 0
    private var currentHeight: Int = 0
    private var lastCsd0: ByteArray = ByteArray(0)
    private var lastCsd1: ByteArray = ByteArray(0)

    fun attachSurface(surface: Surface?) {
        lock.withLock {
            targetSurface = surface
            if (surface == null) {
                releaseCodecLocked()
            } else if (currentWidth > 0 && currentHeight > 0 && lastCsd0.isNotEmpty()) {
                configureCodecLocked(currentWidth, currentHeight, lastCsd0, lastCsd1, surface)
            }
        }
    }

    fun configure(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray) {
        lock.withLock {
            currentWidth = width
            currentHeight = height
            lastCsd0 = csd0
            lastCsd1 = csd1
            val surface = targetSurface ?: return
            configureCodecLocked(width, height, csd0, csd1, surface)
        }
    }

    private fun configureCodecLocked(
        width: Int,
        height: Int,
        csd0: ByteArray,
        csd1: ByteArray,
        surface: Surface
    ) {
        releaseCodecLocked()
        runCatching {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                if (csd0.isNotEmpty()) {
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                }
                if (csd1.isNotEmpty()) {
                    setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(format, surface, null, 0)
            decoder.start()
            codec = decoder
            Log.d(TAG, "Hardware H.264 decoder configured: ${width}x${height}")
        }.onFailure { err ->
            Log.e(TAG, "Failed to configure H.264 decoder", err)
            releaseCodecLocked()
        }
    }

    fun decodeFrame(nalBytes: ByteArray, timestampUs: Long, isKeyFrame: Boolean) {
        lock.withLock {
            val decoder = codec ?: return
            runCatching {
                val inputIndex = decoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputIndex)
                    inputBuffer?.clear()
                    inputBuffer?.put(nalBytes)
                    val flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    decoder.queueInputBuffer(inputIndex, 0, nalBytes.size, timestampUs, flags)
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                while (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true)
                    outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0L)
                }
            }.onFailure { err ->
                Log.w(TAG, "Decode step error: ${err.message}")
            }
        }
    }

    fun release() {
        lock.withLock {
            releaseCodecLocked()
            targetSurface = null
            currentWidth = 0
            currentHeight = 0
            lastCsd0 = ByteArray(0)
            lastCsd1 = ByteArray(0)
        }
    }

    private fun releaseCodecLocked() {
        codec?.let { dec ->
            runCatching { dec.stop() }
            runCatching { dec.release() }
        }
        codec = null
    }
}
