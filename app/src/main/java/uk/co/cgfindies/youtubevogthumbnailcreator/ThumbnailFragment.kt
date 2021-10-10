package uk.co.cgfindies.youtubevogthumbnailcreator

import android.Manifest
import android.content.ContentValues
import android.content.Context
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.random.Random

private const val THUMBNAIL_FRAGMENT_ARG_PROFILE_ID = "argProfileId"

/**
 * Generate thumbnails for videos using a predefined profile
 * Use the [ThumbnailFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class ThumbnailFragment : Fragment() {
    private lateinit var profile: Profile

    companion object Factory {
        val rnd = Random.Default
        
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param profileId The id of the profile that will be used to generate the thumbnail.
         * @return A new instance of fragment ThumbnailFragment.
         */
        @JvmStatic
        fun newInstance(profileId: String) =
            ThumbnailFragment().apply {
                arguments = Bundle().apply {
                    putString(THUMBNAIL_FRAGMENT_ARG_PROFILE_ID, profileId)
                }
            }
    }

    private val options = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
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
            if (lastPermissionRequest == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
                saveImage()
            }
        }
    }

    private val detector = FaceDetection.getClient(options)
    private var isProcessing = false
    private var highestSmileProbability = 0.0f
    private var thumbnailTitle = ""
    private var currentFace: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.RGB_565)
    private var currentFacePosition = Rect(0,0,0,0)
    private var lastPermissionRequest = ""
    private var thumbnailModifiedSinceSave = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.thumbnail_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            val profileId = it.getString(THUMBNAIL_FRAGMENT_ARG_PROFILE_ID)
            val profileManager = ProfileManager(this.requireContext())
            profile = if (profileId.isNullOrEmpty() || profileId == PROFILE_MANAGER_DEFAULT_PROFILE_ID) profileManager.getDefaultProfile() else profileManager.getProfile(profileId)
            showImage()
        }

        requireView().findViewById<Button>(R.id.btn_pick_video).setOnClickListener {
            pickVideo()
        }

        requireView().findViewById<Button>(R.id.btn_save_thumbnail).setOnClickListener {
            saveImage()
        }

        requireView().findViewById<ImageView>(R.id.chosen_image).setOnClickListener {
            Log.i("MAIN", "CLICKED!")
            val titleTextBox = requireView().findViewById<EditText>(R.id.thumbnail_title_text)
            titleTextBox.requestFocus()
            val imm: InputMethodManager = requireActivity().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(titleTextBox, InputMethodManager.SHOW_IMPLICIT)
        }

        requireView().findViewById<EditText>(R.id.thumbnail_title_text).doAfterTextChanged { text: Editable? ->
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

    fun profileChanged(newProfile: Profile) {
        profile = newProfile
        showImage()
    }

    private fun setIsProcessing(newValue: Boolean) {
        Log.i("MAIN", "setting is processing to $newValue")
        isProcessing = newValue
        val newStringId = if (isProcessing) R.string.stop_processing else R.string.pick_video
        activity?.runOnUiThread {
            view?.findViewById<Button>(R.id.btn_pick_video)?.text = getText(newStringId)
        }
    }

    private fun setThumbnailModifiedSinceSave(newValue: Boolean) {
        thumbnailModifiedSinceSave = newValue

        requireActivity().runOnUiThread {
            requireView().findViewById<Button>(R.id.btn_save_thumbnail).isEnabled = thumbnailModifiedSinceSave
        }
    }

    private fun pickVideo() {
        setIsProcessing(!isProcessing)
        if (isProcessing) {
            openGalleryForVideo()
        }
    }

    private fun openGalleryForVideo() {
        getVideoContract.launch("video/*")
    }

    private fun saveImage() {
        if (!Utility.hasPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            lastPermissionRequest = Manifest.permission.WRITE_EXTERNAL_STORAGE
            Utility.getPermission(requireActivity(), requestReadPermissionContract, Manifest.permission.WRITE_EXTERNAL_STORAGE, getString(R.string.request_external_write_permission))
            return
        }

        val image = requireView().findViewById<ImageView>(R.id.chosen_image)
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
        val contentResolver = requireActivity().application.contentResolver

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
        Utility.showMessage(requireActivity(), R.string.thumbnail_saved)
    }

    private suspend fun processImages(uri: Uri) {
        // Can't use mediaFormat, MediaCodex, or MediaEncoder to get rotation until API 23 / 26
        val metaRetriever = MediaMetadataRetriever()
        metaRetriever.setDataSource(this.requireContext(), uri)
        val rotation = (metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: "0").toInt()
        metaRetriever.release()

        val extractor = MediaExtractor()
        val ctx = this.requireContext()
        @Suppress("BlockingMethodInNonBlockingContext")
        (withContext(Dispatchers.Default) {
        extractor.setDataSource(
            ctx,
            uri,
            null
        )
    })

        val processor = VideoProcessor(extractor)

        while (processor.canProcessFurther() && isProcessing) {
            Log.i("MAIN", "Getting image")
            val bufferedImage = processor.next() ?: continue

            Log.i("MAIN", "Converting image")
            val inputImage = InputImage.fromMediaImage(bufferedImage.image, rotation)

            Log.i("MAIN", "Processing start")
            val found = processImage(inputImage)

            Log.i("MAIN", "Processing end")
            if (found) {
                Log.i("MAIN", "Displaying")

                yuv420888TosRGB(bufferedImage.image, rotation)
                Log.i("MAIN", "Bitmap created: ${ currentFace.config }, ${ currentFace.width }, ${ currentFace.height } $rotation")
            }
            bufferedImage.done()
        }

        processor.close()
        setIsProcessing(false)
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
        requireActivity().runOnUiThread {
            val view: ImageView = requireView().findViewById(R.id.chosen_image)
            view.setImageBitmap(thumbnail)
            Log.i("MAIN", "Image displayed")
        }
    }

    private fun compileThumbnail(): Bitmap {
        setThumbnailModifiedSinceSave(true)
        val thumbnail = Bitmap.createBitmap(profile.overlay.copy(profile.overlay.config, true))
        val canvas = Canvas(thumbnail)
        canvas.drawBitmap(currentFace, currentFacePosition, profile.facePosition, null)

        if (thumbnailTitle.isNotEmpty()) {
            val splitTitleTexts = splitTextToFitOnCanvas(thumbnailTitle)
            val paint = Paint(); val r = rnd.nextInt(0, 255); val g = rnd.nextInt(0, 255); val b = rnd.nextInt(0, 255)
            paint.setARGB(255, r, g, b)
            paint.setShadowLayer(5.0f, 10.0f, 10.0f, Color.BLACK)
            paint.typeface = Typeface.create("Impact", Typeface.BOLD)
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

    private fun yuv420888TosRGB(image: Image, rotation: Int) {
        assert (image.format == ImageFormat.YUV_420_888)

        val y = image.planes[0].buffer
        val uv = image.planes[2].buffer

        val yLength = y.capacity()
        val uvLength = uv.capacity()
        val dst = ByteArray(yLength + uvLength) { 0 }
        y.get(dst, 0, yLength)
        uv.get(dst, yLength, uvLength)

        val out = ByteArrayOutputStream()
        val yuvImage = YuvImage(dst, ImageFormat.NV21, image.width, image.height, null)
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 50, out)
        val imageBytes: ByteArray = out.toByteArray()
        val tmp = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

        currentFace.recycle()
        currentFace = if (rotation != 0) {
            val matrix = Matrix()
            matrix.postRotate(270f)
            val tmp2 = Bitmap.createBitmap(tmp,0,0,tmp.width,tmp.height,matrix,true)
            tmp.recycle()
            tmp2
        } else {
            tmp
        }
        showImage()
    }
}