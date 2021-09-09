package uk.co.cgfindies.youtubevogthumbnailcreator

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.IllegalStateException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

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
            openGalleryForVideo()
        }
    }

    private val detector = FaceDetection.getClient(options)

    private var isProcessing = false
    private var highestSmileProbability = 0.0f
    private var thumbnailTitle = ""
    private var facecamPosition = Rect(56, 207, 321, 472)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

    }

    fun openGalleryForVideo(view: View) {
        if (isProcessing) {
            isProcessing = false
            (view as Button).text = getText(R.string.pick_video)
        } else {
            isProcessing = true
            (view as Button).text = getText(R.string.stop_processing)
            openGalleryForVideo()
        }
    }

    private fun openGalleryForVideo() {
        if (!canRead()) {
            getPermission(Manifest.permission.READ_EXTERNAL_STORAGE, getString(R.string.reuest_external_read_permission))
            return
        }

        getVideoContract.launch("video/*")
    }

    private fun canRead(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun getPermission(permission: String, message: String) {
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
            val image = metaRetriever.getFrameAtTime(i) ?: continue
            processImage(image, rotationDegrees)
            image.recycle()
        }

        metaRetriever.release()
    }

    private suspend fun processImage(image: Bitmap, rotation: Int) {
        val inputImage = InputImage.fromBitmap(image, rotation)

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
                            showImage(image.copy(image.config, true), faces[0].boundingBox)
                        }
                    }
                    continuation.resume(Unit)
                }
                .addOnFailureListener { e ->
                    Log.e("MAIN", "Failed to process face", e)
                    continuation.resume(Unit)
                }
        }
    }

    private fun showImage(image: Bitmap, facePosition: Rect) {
        val thumbnail = compileThumbnail(image, facePosition)
        runOnUiThread {
            val view: ImageView = findViewById(R.id.chosen_image)
            view.setImageBitmap(thumbnail)
        }
    }

    private fun compileThumbnail(image: Bitmap, facePosition: Rect): Bitmap {
        val overlay = ResourcesCompat.getDrawable(resources, R.drawable.vlog_thumbnail_overlay, null)?.toBitmap()
            ?: throw IllegalStateException("Overlay resource missing")

        val thumbnail = Bitmap.createBitmap(overlay.copy(overlay.config, true))
        val canvas = Canvas(thumbnail)
        canvas.drawBitmap(image, facePosition, facecamPosition, null)

        if (thumbnailTitle != "") {
            val paint = Paint()
            paint.setARGB(0, rnd.nextInt(0, 255), rnd.nextInt(0, 255), rnd.nextInt(0, 255))
            canvas.drawText(thumbnailTitle, 0.0f, 0.0f, paint)
        }
        return thumbnail
    }
}