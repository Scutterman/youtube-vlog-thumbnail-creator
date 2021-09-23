package uk.co.cgfindies.youtubevogthumbnailcreator

import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment

/**
 * Create a new profile
 * Use the [ProfileAddFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class ProfileAddFragment : Fragment() {
    var onProfileAdded: (profile: Profile) -> Unit = {}

    private val getImageContract = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            requireActivity().runOnUiThread {
                val bitmap = if (Build.VERSION.SDK_INT < 28) {
                    MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
                } else {
                    val source = ImageDecoder.createSource(requireActivity().contentResolver, uri)
                    ImageDecoder.decodeBitmap(source)
                }
                if (bitmap != null) {
                    requireView().findViewById<ImageView>(R.id.overlay_image).setImageBitmap(bitmap)
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.profile_add_fragment, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireView().findViewById<ImageView>(R.id.overlay_image).setOnClickListener {
            getImageContract.launch("image/*")
        }

        requireView().findViewById<Button>(R.id.profile_save).setOnClickListener {
            val name = requireView().findViewById<EditText>(R.id.profile_name).text.toString()
            val left = requireView().findViewById<EditText>(R.id.profile_thumbnail_face_left).text.toString().toIntOrNull()
            val right = requireView().findViewById<EditText>(R.id.profile_thumbnail_face_right).text.toString().toIntOrNull()
            val top = requireView().findViewById<EditText>(R.id.profile_thumbnail_face_top).text.toString().toIntOrNull()
            val bottom = requireView().findViewById<EditText>(R.id.profile_thumbnail_face_bottom).text.toString().toIntOrNull()
            val overlay = (requireView().findViewById<ImageView>(R.id.overlay_image).drawable as BitmapDrawable?)?.bitmap

            // TODO:: show validation messages if one of these are wrong
            if (name.isNotEmpty() && left != null && right != null && top != null && bottom != null && overlay != null) {
                val newProfile = ProfileManager(requireContext())
                    .addProfile(name, overlay, Rect(left, top, right, bottom))
                onProfileAdded(newProfile)
            }
        }
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         * @param onProfileAdded A lambda that gets called when a new profile has been added
         * @return A new instance of fragment ProfileAddFragment.
         */
        @JvmStatic
        fun newInstance(onProfileAdded: ((profile: Profile) -> Unit)?): ProfileAddFragment {
            val fragment = ProfileAddFragment()
            if (onProfileAdded != null) {
                fragment.onProfileAdded = onProfileAdded
            }
            return fragment
        }
    }
}
