 package uk.co.cgfindies.youtubevogthumbnailcreator

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
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

        val button = requireView().findViewById<Button>(R.id.btn_upload_video)
        button.setOnClickListener {
            button.isEnabled = false
            showChannels()
            button.isEnabled = true
        }
    }

    private fun showChannels() {
        Log.i("UPLOAD", "Showing channels")
        setOutputText("", true)

        getChannelList { channels ->
            if (channels == null || channels.isEmpty()) {
                Log.i("UPLOAD", "No results")
                Utility.showMessage(requireActivity(), R.string.no_results)
            } else {
                Log.i("UPLOAD", "Got data")
                val channelSummary = channels.map { channel ->
                    "This channel's ID is " + channel.id + ". " +
                    "Its title is '" + channel.snippet.title + "', " +
                    "and it has " + channel.statistics.viewCount + " views."
                }
                setOutputText("Data retrieved using the YouTube Data API:\n\n" + TextUtils.join("\n\n", channelSummary))

                getPlaylistVideos("UUQLfY7-dNbkkQKXVkEttSKA") { items ->
                    if (items == null || items.isEmpty()) {
                        Log.i("VIDEOS", "No results")
                        Utility.showMessage(requireActivity(), R.string.no_results)
                    } else {
                        Log.i("VIDEOS", "Got data")
                        val videos = items.map { video ->
                            "This video's title is '" + video.snippet.title + "', " +
                             "and it has " + video.status + " views."
                        }
                        setOutputText(
                            "Data retrieved using the YouTube Data API:\n\n" + TextUtils.join(
                                "\n\n",
                                videos
                            )
                        )
                    }
                }
            }
        }
    }

    private fun setOutputText(text: String, overwrite: Boolean = false) {
        val view = requireView().findViewById<TextView>(R.id.upload_output)
        val initial = if (overwrite) "" else view.text.toString()
        view.text = initial + text
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