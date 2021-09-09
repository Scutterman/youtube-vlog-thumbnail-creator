package uk.co.cgfindies.youtubevogthumbnailcreator

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class MainActivity : AppCompatActivity() {
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

    private var highestSmileProbability = 0.0f
    private var bestBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

    }

    fun openGalleryForVideo(_view: View) {
        openGalleryForVideo()
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
        bestBitmap = null

        val metaRetriever = MediaMetadataRetriever()
        metaRetriever.setDataSource(this, uri)

        val duration = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION) ?: return
        val rotation = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0"

        val durationUs = duration.toLong() * 1000
        val rotationDegrees = rotation.toInt()

        Log.i("MAIN", "rotation $rotationDegrees")
        for (i in 1..durationUs step 1000000) {
            val image = metaRetriever.getFrameAtTime(i) ?: continue
            processImage(image, rotationDegrees)
            //image.recycle()
            break
        }

        metaRetriever.release()

        val best = bestBitmap
        if (best != null) {
            showImage(best)
        }
    }

    private suspend fun processImage(image: Bitmap, rotation: Int) {
        val inputImage = InputImage.fromBitmap(image, rotation)
        showImage(inputImage.bitmapInternal)

        return suspendCoroutine { continuation ->
            detector.process(inputImage)
                .addOnSuccessListener { faces ->
                    Log.i("MAIN", "Got faces? ${faces.size}")

                    if (faces.count() == 1) {
                        val smileProb = faces[0]?.smilingProbability ?: 0.0f
                        Log.i("MAIN", "Got smiles? $smileProb")
                        if (smileProb > highestSmileProbability) {
                            highestSmileProbability = smileProb
                            bestBitmap = image.copy(image.config, true)
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

    private fun showImage(image: Bitmap) {
        runOnUiThread {
            val view: ImageView = findViewById(R.id.chosen_image)
            view.setImageBitmap((image))
        }
    }
}