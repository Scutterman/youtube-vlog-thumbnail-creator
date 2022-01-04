 package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Intent
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
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import com.google.api.services.youtube.model.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.cgfindies.youtubevogthumbnailcreator.service.APIStatus
import uk.co.cgfindies.youtubevogthumbnailcreator.service.Upload
import uk.co.cgfindies.youtubevogthumbnailcreator.service.YouTube

class UploadFragment: Fragment() {
    private lateinit var youtube: YouTube

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_upload, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        youtube = YouTube(requireContext())
        val upload = requireView().findViewById<Button>(R.id.btn_upload_video)
        upload.setOnClickListener {
            upload.isEnabled = false
            CoroutineScope(Dispatchers.IO).launch {
                showChannels()
            }
            upload.isEnabled = true
        }

        val resetCredentials = requireView().findViewById<Button>(R.id.btn_reset_credentials)
        resetCredentials.setOnClickListener {
            Utility.resetCredentials(requireContext())
        }

        requireView().findViewById<Button>(R.id.btn_do_notification).setOnClickListener {
            val uploadWorkRequest: WorkRequest =
                OneTimeWorkRequestBuilder<Upload>()
                    .build()

            WorkManager
                .getInstance(requireContext())
                .enqueue(uploadWorkRequest)
        }
    }

    private suspend fun showChannels() {
        Log.i("UPLOAD", "Showing channels")
        setOutputText("")

        val status = youtube.apiStatus()

        if (status == APIStatus.NO_NETWORK) {
            Log.i("UPLOAD", "No Network")
            Utility.showMessage(requireActivity(), R.string.network_unavailable)
            return
        } else if (status == APIStatus.REQUIRES_AUTH) {
            val intent = Intent(context, AuthActivity::class.java)
            requireContext().startActivity(intent)
            return
        }

        val channels = youtube.getChannelList()
        if (channels == null || channels.isEmpty()) {
            Log.i("UPLOAD", "No results")
            Utility.showMessage(requireActivity(), R.string.no_results)
        } else {
            Log.i("UPLOAD", "Got data")
            val channelSummary = channels.map { channel ->
                requireContext().resources.getString(R.string.upload_channel_description, channel.snippet.title, channel.statistics.viewCount)
            }

            setOutputText("Data retrieved using the YouTube Data API:\n\n" + TextUtils.join("\n\n", channelSummary))

            // A bit of a hack here so we don't need to fetch channel playlists and search for the Uploads playlist id.
            // Channel id seems to be the characters "UC" followed by an identifier.
            // The playlist id for Uploads seems to be "UU" followed by that same identifier
            // Therefore, to get the Uploads playlist id, strip the "UC" from the beginning of the channel id and add "UU" in its place
            val playlistId = "UU" + channels[0].id.substring(2)

            val items = youtube.getPlaylistVideos(playlistId)
            if (items == null || items.isEmpty()) {
                Log.i("VIDEOS", "No results")
                Utility.showMessage(requireActivity(), R.string.no_results)
            } else {
                Log.i("VIDEOS", "Got data")
                populateVideoList(items.toMutableList())
            }
        }
    }

    private suspend fun setOutputText(text: String) {
        withContext(Dispatchers.Main) {
            requireView().findViewById<TextView>(R.id.upload_output).text = text
        }
    }

     private suspend fun populateVideoList(videos: MutableList<PlaylistItem>) {
         withContext(Dispatchers.Main) {
             val list = requireActivity().findViewById<ListView>(R.id.video_list)
             val videoAdapter = VideoAdapter(requireContext(), videos)
             list.adapter = videoAdapter
             Log.i("ProfileAdapter", "Done populating video list")
         }
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