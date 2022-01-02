package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.fragment.app.Fragment
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.http.*
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Channel
import com.google.api.services.youtube.model.PlaylistItem
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Serializable
data class AccessTokenResponse(
    val access_token: String,
    val expiry_date: Long,
    val token_type: String,
    val scope: String,
    val refresh_token: String
)

/**
 * TODO::At least some of this should be in a service so we don't have to deal with it getting destroyed
 */
@DelicateCoroutinesApi
open class YoutubeBase: Fragment() {
    private lateinit var networkMonitor: NetworkMonitor

    private var action: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        networkMonitor = NetworkMonitor.getInstance(requireContext())
    }

    override fun onResume() {
        super.onResume()
        if (action != null) {
            resultsFromApi
        }
    }

    fun getChannelList(onResult: (List<Channel>?) -> Unit) {
        action = {
            Log.i("UPLOAD", "Performing channel list action")
            MakeRequestTask<List<Channel>>({ apiClient ->
                Log.i("UPLOAD", "data from api")
                val result = apiClient.channels().list("snippet,contentDetails,statistics")
                    .setMine(true)
                    .execute()

                Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
                action = null
                return@MakeRequestTask result.items?.toList() ?: emptyList()
            }, onResult).execute()
            Log.i("UPLOAD", "Executed channel list item")
        }

        return resultsFromApi
    }

    fun getPlaylistVideos(playlistId: String, onResult: (List<PlaylistItem>?) -> Unit) {
        action = {
            Log.i("UPLOAD", "Performing video list action")
            MakeRequestTask<List<PlaylistItem>>({ apiClient ->
                Log.i("UPLOAD", "data from api")
                val result = apiClient.playlistItems().list("snippet,contentDetails,status")
                    .setPlaylistId(playlistId)
                    .setMaxResults(10)
                    .execute()

                Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
                action = null
                return@MakeRequestTask result.items?.toList() ?: emptyList()
            }, onResult).execute()
            Log.i("UPLOAD", "Executed video list action")
        }

        return resultsFromApi
    }

    /**
     * Attempt to call the API, after verifying that all the preconditions are
     * satisfied. The preconditions are: Google Play Services installed, an
     * account was selected and the device currently has online access. If any
     * of the preconditions are not satisfied, the app will prompt the user as
     * appropriate.
     */
    private val resultsFromApi: Unit
        get() {
            Utility.getAuthentication(requireContext()) { auth ->
                if (auth == null) {
                    val intent = Intent(requireContext(), AuthActivity::class.java)
                    startActivity(intent)
                } else if (!networkMonitor.isConnected) {
                    Log.i("UPLOAD", "No Network")
                    Utility.showMessage(requireActivity(), R.string.network_unavailable)
                } else {
                    Log.i("UPLOAD", "Make request task")
                    action?.invoke()
                }
            }
        }

    /**
     * An asynchronous task that handles the YouTube Data API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private inner class MakeRequestTask<Result>
        constructor(private val fetchData: (apiClient: YouTube) -> Result, private val onResult: (result: Result?) -> Unit) :
            CoroutinesAsyncTask<Void?, Void?, Result>("MakeYoutubeRequest"),
        HttpRequestInitializer {

        var auth: AccessTokenResponse? = null

        /**
         * Background task to call YouTube Data API.
         * @param params no parameters needed for this task.
         */
        override fun doInBackground(vararg params: Void?): Result? {
            return try {
                Log.i("UPLOAD", "Setting up the api client")
                val transport = AndroidHttp.newCompatibleTransport()
                val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
                val apiClient = YouTube.Builder(transport, jsonFactory, this)
                    .setApplicationName("YouTube Vlog Thumbnail")
                    .build()
                Log.i("UPLOAD", "Trying to get the data")
                fetchData(apiClient)
            } catch (e: Exception) {
                Log.e("UPLOAD", "do background error", e)
                cancel(true, e)
                null
            }
        }

        override suspend fun onPreExecute(): Unit = suspendCoroutine { cont ->
            Log.i("UPLOAD", "Pre execute")
            Utility.getAuthentication(requireContext()) { auth ->
                Log.i("UPLOAD", "Fetched auth")
                this.auth = auth
                Log.i("UPLOAD", "Resuming task flow")
                cont.resume(Unit)
            }
        }

        override fun onPostExecute(result: Result?) {
            Log.i("UPLOAD", "Post execute")
            onResult(result)
        }

        override fun onCancelled(e: java.lang.Exception?) {
            Log.i("UPLOAD", "Cancelled")
            if (e != null) {
                Log.e("UPLOAD", "Error while uploading", e)
                Utility.showMessage(requireActivity(), R.string.error_uploading)
            } else {
                Utility.showMessage(requireActivity(), R.string.upload_cancelled)
            }
        }

        override fun initialize(request: HttpRequest?) {
            Log.i("UPLOAD", "Initializing the request")
            if (request == null) {
                Log.i("UPLOAD", "No request, nothing to do")
                return
            }

            Log.i("UPLOAD", "Checking the auth")
            val auth = this.auth ?: throw Exception("Set authentication before running a request")

            Log.i("UPLOAD", "Setting request handler")
            val handler = RequestHandler(auth)
            request.interceptor = handler
            request.unsuccessfulResponseHandler = handler
            Log.i("UPLOAD", "Initialize complete")
        }
    }

    private inner class RequestHandler (val auth: AccessTokenResponse) : HttpExecuteInterceptor,
        HttpUnsuccessfulResponseHandler {

        override fun intercept(request: HttpRequest) {
            Log.i("UPLOAD", "Intercepted the request, adding an access token")
            request.headers.authorization = "Bearer ${ auth.access_token }"
        }

        override fun handleResponse(
            request: HttpRequest, response: HttpResponse, supportsRetry: Boolean
        ): Boolean {
            Log.i("UPLOAD", "Request failed with the status" + response.statusCode.toString())
            // If the response was 401, mark the credentials as needing refreshing.
            // If the refresh token is no longer valid,
            // the API Server will not be able to return an access token and the user will be prompted to authenticate again
            if (response.statusCode == 401) {
                Utility.setRequiresRefresh(requireContext())
            }
            return false
        }
    }
}
