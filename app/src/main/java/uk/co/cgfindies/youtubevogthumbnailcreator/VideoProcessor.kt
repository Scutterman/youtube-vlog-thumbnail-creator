package uk.co.cgfindies.youtubevogthumbnailcreator

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

const val TAG = "VideoProcessor"

class VideoProcessor(private val extractor: MediaExtractor) : MediaCodec.Callback() {
    private val decoder: MediaCodec
    private var isEOS = false
    private var width = -1
    private var height = -1

    private var waitingForImage: Continuation<BufferedImage?>? = null

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
        Log.i(TAG, "Output format initially $format")
        decoder.configure(format, null, null, 0)
        decoder.start()
    }

    suspend fun next(): BufferedImage? {
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
                waitingForImage?.resume(BufferedImage(image) {
                    decoder.releaseOutputBuffer(outIndex, false)
                })

                waitingForImage = null
            }
        }
    }

    override fun onError(p0: MediaCodec, e: MediaCodec.CodecException) {
        Log.e(TAG, "Error in media codec", e)
    }

    override fun onOutputFormatChanged(p0: MediaCodec, p1: MediaFormat) {
        Log.i(TAG, "Output format changed $p1")
    }

    class BufferedImage(val image: Image, private val doneWithImage: () -> Unit) {
        fun done() {
            image.close()
            doneWithImage()
        }
    }
}
