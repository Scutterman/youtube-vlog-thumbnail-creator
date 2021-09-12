package uk.co.cgfindies.youtubevogthumbnailcreator

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import android.media.Image
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.truncate
import kotlin.random.Random

const val TITLE_LINE_LENGTH = 13

class MainActivity : AppCompatActivity() {
    companion object Factory {
        val rnd = Random.Default
    }

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
        .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
        .build()

    private val getVideoContract = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            Log.d("MAIN", "We have a uri")
            CoroutineScope(Dispatchers.Default).launch {
                processImages(uri)
            }
        }
    }

    private val requestReadPermissionContract = registerForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        if (result) {
            if (lastPermissionRequest == Manifest.permission.READ_EXTERNAL_STORAGE) {
                openGalleryForVideo()
            } else if (lastPermissionRequest == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                saveImage()
            }
        }
    }

    private val detector = FaceDetection.getClient(options)

    private var isProcessing = false
    private var highestSmileProbability = 0.0f
    private var thumbnailTitle = ""
    private var facecamPosition = Rect(56, 207, 321, 472)
    private var currentFace: Bitmap = Bitmap.createBitmap(265, 265, Bitmap.Config.RGB_565)
    private var currentFacePosition = Rect(0,0,0,0)
    private var lastPermissionRequest = ""
    private var thumbnailModifiedSinceSave = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btn_pick_video).setOnClickListener {
            pickVideo()
        }

        findViewById<Button>(R.id.btn_save_thumbnail).setOnClickListener {
            saveImage()
        }

        findViewById<ImageView>(R.id.chosen_image).setOnClickListener {
            Log.i("MAIN", "CLICKED!")
            val titleTextBox = findViewById<EditText>(R.id.thumbnail_title_text)
            titleTextBox.requestFocus()
            val imm: InputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(titleTextBox, InputMethodManager.SHOW_IMPLICIT)
        }

        findViewById<EditText>(R.id.thumbnail_title_text).doAfterTextChanged { text: Editable? ->
            thumbnailTitle = text?.toString() ?: ""
            showImage()
        }

        showImage()
        setThumbnailModifiedSinceSave(false)
    }

    override fun onDestroy() {
        super.onDestroy()
        setIsProcessing(false)
    }

    private fun setIsProcessing(newValue: Boolean) {
        Log.i("MAIN", "setting is processing to $newValue")
        val button: Button = findViewById(R.id.btn_pick_video)
        isProcessing = newValue
        val newStringId = if (isProcessing) R.string.stop_processing else R.string.pick_video
        button.text = getText(newStringId)
    }

    private fun setThumbnailModifiedSinceSave(newValue: Boolean) {
        thumbnailModifiedSinceSave = newValue

        runOnUiThread {
            findViewById<Button>(R.id.btn_save_thumbnail).isEnabled = thumbnailModifiedSinceSave
        }
    }

    private fun pickVideo() {
        setIsProcessing(!isProcessing)
        if (isProcessing) {
            openGalleryForVideo()
        }
    }

    private fun openGalleryForVideo() {
        if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
            getPermission(Manifest.permission.READ_EXTERNAL_STORAGE, getString(R.string.request_external_read_permission))
            return
        }

        getVideoContract.launch("video/*")
    }

    private fun saveImage() {
        if (!hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            getPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE, getString(R.string.request_external_read_permission))
            return
        }

        val image = findViewById<ImageView>(R.id.chosen_image)
        val bitmap = (image.drawable as BitmapDrawable).bitmap

        val filename = thumbnailTitle
        var fos: OutputStream?
        var imageUri: Uri?
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        //use application context to get contentResolver
        val contentResolver = application.contentResolver

        contentResolver.also { resolver ->
            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            fos = imageUri?.let { resolver.openOutputStream(it) }
        }

        fos?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 70, it) }

        if (imageUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            contentResolver.update(imageUri!!, contentValues, null, null)
        }

        setThumbnailModifiedSinceSave(false)
        Log.i("MAIN", "Saved successfully")
        showMessage(R.string.thumbnail_saved)
    }

    private fun showMessage(messageId: Int) {
        val view = findViewById<View>(android.R.id.content)
        Snackbar.make(view, messageId, Snackbar.LENGTH_LONG).show()
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun getPermission(permission: String, message: String) {
        lastPermissionRequest = permission
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
            val alertBuilder = AlertDialog.Builder(this)
            alertBuilder.setCancelable(true)
            alertBuilder.setMessage(message)
            alertBuilder.setPositiveButton(android.R.string.ok
            ) { _, _ ->
                requestReadPermissionContract.launch(permission)
            }
        } else {
            requestReadPermissionContract.launch(permission)
        }
    }

    private suspend fun processImages(uri: Uri) {
        highestSmileProbability = 0.0f

        val test = true

        if (test) {
            processImages2(uri)
            return
        }

        val metaRetriever = MediaMetadataRetriever()
        metaRetriever.setDataSource(this, uri)

        val duration = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return
        val rotation = "0" // For some reason this comes out at 270 when MLKit is expecting 0 - metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0"

        val durationUs = duration.toLong() * 1000
        val rotationDegrees = rotation.toInt()

        Log.i("MAIN", "rotation $rotationDegrees")
        for (i in 1..durationUs step 1000000) {
            if (!isProcessing) {
                return
            }

            Log.i("MAIN", "Getting image")
            val image = metaRetriever.getFrameAtTime(i) ?: continue
            Log.i("MAIN", "Converting to input")
            val inputImage = InputImage.fromBitmap(image, rotationDegrees)
            Log.i("MAIN", "Processing start")
            val found = processImage(inputImage)
            Log.i("MAIN", "Processing end")
            if (found) {
                Log.i("MAIN", "Displaying")
                currentFace = image.copy(image.config, true)
                showImage()
            }
            Log.i("MAIN", "Recycling")
            image.recycle()
            Log.i("MAIN", "Done")
        }

        metaRetriever.release()
    }

    private suspend fun processImages2(uri: Uri) {

        val extractor = MediaExtractor()
        val that = this
        @Suppress("BlockingMethodInNonBlockingContext")
        withContext(Dispatchers.Default) { extractor.setDataSource(that, uri, null) }

        val processor = VideoProcessor(extractor)
        var firstRun = true

        while (processor.canProcessFurther() && isProcessing) {
            Log.i("MAIN", "Getting image")
            val bufferedImage = processor.next() ?: continue

            Log.i("MAIN", "Converting image")
            val inputImage = InputImage.fromMediaImage(bufferedImage.image, 0)

            Log.i("MAIN", "Processing start")
            val found = processImage(inputImage)

            Log.i("MAIN", "Processing end")
            if (found || firstRun) {
                // firstRun = false
                Log.i("MAIN", "Displaying")
                val y = bufferedImage.image.planes[0].buffer.capacity()
                val u = bufferedImage.image.planes[1].buffer.capacity()
                val v = bufferedImage.image.planes[2].buffer.capacity()
                val format = bufferedImage.image.format
                val format2 = inputImage.format
                Log.e("MAIN", "Image Info: $y, $u, $v, $format, $format2")

                val pixels = yuv420888ToSRGB(bufferedImage.image)
                // val tmp = getBitmap(bufferedImage.image)

                val tmp = Bitmap.createBitmap(pixels, bufferedImage.image.width, bufferedImage.image.height, Bitmap.Config.ARGB_8888)

                if (tmp == null) {
                    Log.e("MAIN", "Bitmap could not be decoded from input image!")
                } else {
                    Log.i("MAIN", "Bitmap created: ${ tmp.config }, ${ tmp.width }, ${ tmp.height }")
                    currentFace = tmp
                }
                showImage()
            }
            bufferedImage.done()
            break
        }

        processor.close()
    }

    private suspend fun processImage(inputImage: InputImage): Boolean {
        var faceFound = false

        return suspendCoroutine { continuation ->
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    Log.i("MAIN", "Got faces? ${faces.size}")

                    if (faces.count() == 1) {
                        val smileProb = faces[0]?.smilingProbability ?: 0.0f
                        Log.i("MAIN", "Got smiles? $smileProb")
                        if (isProcessing && smileProb > highestSmileProbability) {
                            Log.i("MAIN", "New highest smile probability, showing the image")
                            highestSmileProbability = smileProb
                            currentFacePosition = faces[0].boundingBox
                            faceFound = true
                        }
                    }
                    continuation.resume(faceFound)
                }
                .addOnFailureListener { e ->
                    Log.e("MAIN", "Failed to process face", e)
                    continuation.resume(faceFound)
                }
        }
    }

    private fun showImage() {
        val thumbnail = compileThumbnail()
        runOnUiThread {
            val view: ImageView = findViewById(R.id.chosen_image)
            view.setImageBitmap(thumbnail)
            Log.i("MAIN", "Image displayed")
        }
    }

    private fun compileThumbnail(): Bitmap {
        val overlay = ResourcesCompat.getDrawable(resources, R.drawable.vlog_thumbnail_overlay, null)?.toBitmap()
            ?: throw IllegalStateException("Overlay resource missing")

        setThumbnailModifiedSinceSave(true)
        val thumbnail = Bitmap.createBitmap(overlay.copy(overlay.config, true))
        val canvas = Canvas(thumbnail)
        canvas.drawBitmap(currentFace, currentFacePosition, facecamPosition, null)

        if (thumbnailTitle.isNotEmpty()) {
            val splitTitleTexts = splitTextToFitOnCanvas(thumbnailTitle)
            val paint = Paint(); val r = rnd.nextInt(0, 255); val g = rnd.nextInt(0, 255); val b = rnd.nextInt(0, 255)
            paint.setARGB(255, r, g, b)
            paint.setShadowLayer(5.0f, 10.0f, 10.0f, Color.BLACK)
            paint.typeface = Typeface.create("Impact",Typeface.BOLD)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 100.0f

            var yPosition = 200.0f

            for (i in splitTitleTexts.indices) {
                val text = splitTitleTexts[i]

                yPosition += if (i == 0) {
                    canvas.save()
                    canvas.rotate(-6.5f)
                    canvas.drawText(text, 600.0f, yPosition, paint)
                    canvas.restore()
                    100
                } else {
                    canvas.drawText(text, 770.0f, yPosition, paint)
                    150
                }
            }
        }
        return thumbnail
    }

    private fun splitTextToFitOnCanvas(text: String): List<String> {
        var remainingText = text.uppercase()
        val splitText = mutableListOf<String>()

        while (remainingText.isNotEmpty()) {
            if (remainingText.length <= TITLE_LINE_LENGTH) {
                splitText.add((remainingText))
                break
            }

            for (i in (TITLE_LINE_LENGTH) downTo 0) {
                if (remainingText[i] == ' ') {
                    splitText.add(remainingText.substring(0, i))
                    remainingText = remainingText.substring(i+1)
                    break
                }

                if (i == 0) {
                    splitText.add(remainingText.substring(0, TITLE_LINE_LENGTH - 1) + "-")
                    remainingText = remainingText.substring(TITLE_LINE_LENGTH-1)
                }
            }
        }
        return splitText
    }

    private fun getBitmap(image: Image): Bitmap? {
        assert (image.format == ImageFormat.YUV_420_888)
        val planeCount = image.planes.size
        var bufferSize = 0
        for (i in 0 until planeCount) {
            bufferSize += image.planes[i].buffer.capacity()
        }

        val ib = ByteBuffer.allocate(bufferSize)

        for (i in 0 until planeCount) {
            ib.put(image.planes[i].buffer)
        }

        try {
            val yuvImage = YuvImage(ib.array(), ImageFormat.YUV_420_888, image.width, image.height, null)
            val stream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, stream)
            val bmp = BitmapFactory.decodeByteArray(stream.toByteArray(), 0, stream.size())
            stream.close()
            return bmp
        } catch (e: Exception) {
            Log.e("VisionProcessorBase", "Error: " + e.message)
        }
        return null
    }

    private fun yuv420888ToSRGB(image: Image): IntArray {
        assert (image.format == ImageFormat.YUV_420_888)

        val pixels = image.width * image.height
        val out = IntArray(pixels)

        val width = image.width
        val height = image.height
        val y = image.planes[0].buffer
        val u = image.planes[1].buffer
        val v = image.planes[2].buffer
        var pixelChannelIndex = 0

        for (row in 0 until height) {
            for (col in 0 until width) {
                val yy = y[(row * width) + 1]
                val uu = u[((row / 2) * (width / 2)) + (col / 2)]
                val vv = v[((row / 2) * (width / 2)) + (col / 2)]

                val r  = clampToRGB(1.164 * (yy - 16) + 1.596 * (vv - 128))
                val g  = clampToRGB(1.164 * (yy - 16) - 0.813 * (vv - 128) - 0.391 * (uu - 128))
                val b  = clampToRGB(1.164 * (yy - 16) + 2.018 * (uu - 128))

                out[pixelChannelIndex++] = Color.rgb(r, g, b)
            }
        }

        return out
    }

    private fun clampToRGB(input: Double): Int {
        val inputInt = truncate(input).toInt()
        return if (inputInt < 0) 0 else if (inputInt > 255) 255 else inputInt
    }
}
