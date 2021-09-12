package uk.co.cgfindies.youtubevogthumbnailcreator

import android.media.*
import android.util.Log
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ReadOnlyBufferException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.experimental.inv

const val TAG = "VideoProcessor"

class VideoProcessor(private val extractor: MediaExtractor) : MediaCodec.Callback() {
    private val decoder: MediaCodec
    private var isEOS = false
    private var width = -1
    private var height = -1

    private var waitingForImage: Continuation<ByteArray?>? = null

    init {
        var tempDecoder: MediaCodec? = null
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) {
                extractor.selectTrack(i)
                try {
                    tempDecoder = MediaCodec.createDecoderByType(mime)
                    width = format.getInteger(MediaFormat.KEY_WIDTH)
                    height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    break
                } catch (e: IOException) {
                    Log.e("MAIN", "Could not get decoder", e)
                }
            }
        }

        if (tempDecoder == null || format == null) {
            throw Exception("No decoder found for input media")
        }

        decoder = tempDecoder
        decoder.setCallback(this)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar)
        Log.i(TAG, "Output format initially $format")
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    suspend fun next(): ByteArray? {
        if (isEOS) {
            return null
        }

        return suspendCoroutine { continuation ->
            waitingForImage = continuation
        }
    }

    fun close() {
        decoder.stop()
        decoder.release()
        extractor.release()
    }

    fun canProcessFurther(): Boolean {
        return !isEOS
    }

    fun getWidth(): Int {
        return width
    }

    fun getHeight(): Int {
        return height
    }

    override fun onInputBufferAvailable(p0: MediaCodec, inIndex: Int) {
        if (inIndex < 0) {
            Log.i(TAG, "Cannot proceed because input buffer index is $inIndex")
            return
        }

        val buffer = decoder.getInputBuffer(inIndex) ?: return

        Log.i(TAG, "Advancing to sync flag")
        while (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC == 0) {
            extractor.advance()
        }

        val sampleSize = extractor.readSampleData(buffer, 0)
        if (sampleSize >= 0) {
            Log.i(TAG, "Got valid data into buffer")
            try {
                Log.i(TAG, "Queueing input buffer")
                decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
                Log.i(TAG, "Queue and advance complete")
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Could not queue input buffer", e)
            }
        } else {
            Log.i(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM")
            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            isEOS = true
        }
    }

    override fun onOutputBufferAvailable(p0: MediaCodec, outIndex: Int, p2: MediaCodec.BufferInfo) {
        if (outIndex >= 0) {
            Log.i(TAG, "got output index $outIndex")
            val image = p0.getOutputImage(outIndex)
            if (image != null) {
                Log.i(TAG, "Acquired image")
                waitingForImage?.resume(convertYUV420888ToNV21(image))
                waitingForImage = null
            }
            decoder.releaseOutputBuffer(outIndex, false)
        }
    }

    override fun onError(p0: MediaCodec, e: MediaCodec.CodecException) {
        Log.e(TAG, "Error in media codec", e)
    }

    override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
        Log.i(TAG, "Output format changed $p1")
    }

    private fun convertYUV420888ToNV21(image: Image?): ByteArray? {
        if (image == null) {
            return null
        }

        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = cloneByteBuffer(image.planes[2].buffer) // V - we clone this one because it's the only one modified.
        var rowStride: Int = image.planes[0].rowStride
        assert(image.planes[0].pixelStride == 1)
        var pos = 0
        if (rowStride == width) { // likely
            yBuffer.get(nv21, 0, ySize)
            pos += ySize
        } else {
            var yBufferPos = -rowStride // not an actual position
            while (pos < ySize) {
                yBufferPos += rowStride
                yBuffer.position(yBufferPos)
                yBuffer.get(nv21, pos, width)
                pos += width
            }
        }
        rowStride = image.planes[2].rowStride
        val pixelStride: Int = image.planes[2].pixelStride
        assert(rowStride == image.planes[1].rowStride)
        assert(pixelStride == image.planes[1].pixelStride)
        if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
            // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
            val savePixel: Byte = vBuffer.get(1)
            try {
                vBuffer.put(1, savePixel.inv())
                if (uBuffer.get(0) == savePixel.inv()) {
                    vBuffer.put(1, savePixel)
                    vBuffer.position(0)
                    uBuffer.position(0)
                    vBuffer.get(nv21, ySize, 1)
                    uBuffer.get(nv21, ySize + 1, uBuffer.remaining())
                    return nv21 // shortcut
                }
            } catch (ex: ReadOnlyBufferException) {
                // unfortunately, we cannot check if vBuffer and uBuffer overlap
            }

            // unfortunately, the check failed. We must save U and V pixel by pixel
            vBuffer.put(1, savePixel)
        }

        // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
        // but performance gain would be less significant
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val vuPos = col * pixelStride + row * rowStride
                nv21[pos++] = vBuffer.get(vuPos)
                nv21[pos++] = uBuffer.get(vuPos)
            }
        }
        return nv21
    }

    private fun cloneByteBuffer(original: ByteBuffer): ByteBuffer {
        // Create clone with same capacity as original.
        val clone =
            if (original.isDirect) ByteBuffer.allocateDirect(original.capacity()) else ByteBuffer.allocate(
                original.capacity()
            )

        // Create a read-only copy of the original.
        // This allows reading from the original without modifying it.
        val readOnlyCopy = original.asReadOnlyBuffer()

        // Flip and read from the original.
        readOnlyCopy.flip()
        clone.put(readOnlyCopy)
        return clone
    }
}
