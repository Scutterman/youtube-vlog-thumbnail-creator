 package uk.co.cgfindies.youtubevogthumbnailcreator

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.api.services.youtube.model.PlaylistItem
import kotlinx.coroutines.DelicateCoroutinesApi

 /**
 * A simple [Fragment] subclass.
 * Use the [UploadFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
 @DelicateCoroutinesApi
class UploadFragment : YoutubeBase() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_upload, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val upload = requireView().findViewById<Button>(R.id.btn_upload_video)
        upload.setOnClickListener {
            upload.isEnabled = false
            showChannels()
            upload.isEnabled = true
        }

        val resetCredentials = requireView().findViewById<Button>(R.id.btn_reset_credentials)
        resetCredentials.setOnClickListener {
            Utility.resetCredentials(requireContext())
        }
    }

    private fun showChannels() {
        Log.i("UPLOAD", "Showing channels")
        setOutputText("")

        getChannelList { channels ->
            if (channels == null || channels.isEmpty()) {
                Log.i("UPLOAD", "No results")
                Utility.showMessage(requireActivity(), R.string.no_results)
            } else {
                Log.i("UPLOAD", "Got data")
                val channelSummary = channels.map { channel ->
                    requireContext().resources.getString(R.string.upload_channel_description, channel.snippet.title, channel.statistics.viewCount)
                }

                setOutputText("Data retrieved using the YouTube Data API:\n\n" + TextUtils.join("\n\n", channelSummary))

                getPlaylistVideos("UUQLfY7-dNbkkQKXVkEttSKA") { items ->
                    if (items == null || items.isEmpty()) {
                        Log.i("VIDEOS", "No results")
                        Utility.showMessage(requireActivity(), R.string.no_results)
                    } else {
                        Log.i("VIDEOS", "Got data")
                        populateVideoList(items.toMutableList())
                    }

                }
            }
        }
    }

    private fun setOutputText(text: String) {
        requireView().findViewById<TextView>(R.id.upload_output).text = text
    }

     private fun populateVideoList(videos: MutableList<PlaylistItem>) {
         val list = requireActivity().findViewById<ListView>(R.id.video_list)
         val videoAdapter = VideoAdapter(requireContext(), videos)
         list.adapter = videoAdapter
         Log.i("ProfileAdapter", "Done populating video list")
     }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         * @return A new instance of fragment UploadFragment.
         */
        @JvmStatic
        fun newInstance() =
            UploadFragment()
    }
}