package uk.co.cgfindies.youtubevogthumbnailcreator.service

import android.content.Context
import android.util.Log
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.http.*
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.model.Channel
import com.google.api.services.youtube.model.PlaylistItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import uk.co.cgfindies.youtubevogthumbnailcreator.NetworkMonitor
import uk.co.cgfindies.youtubevogthumbnailcreator.Utility
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

enum class APIStatus {
    REQUIRES_AUTH,
    NO_NETWORK,
    OKAY
}

class YouTube(val context: Context) {
    // TODO:: Have this configurable so that the YouTube class knows whether it requires an un-metred connection
    private var networkMonitor: NetworkMonitor = NetworkMonitor.getInstance(context)

    private var auth: AccessTokenResponse? = null

    suspend fun apiStatus(): APIStatus {
        if (!networkMonitor.isConnected) {
            return APIStatus.NO_NETWORK
        }

        auth = Utility.getAuthentication(context)
        return if (auth == null) APIStatus.REQUIRES_AUTH else APIStatus.OKAY

    }

    private fun getApiClient(): YouTube {
        val auth = this.auth ?: throw Exception("Set authentication before running a request")
        Log.i("UPLOAD", "Setting up the api client")
        val transport = AndroidHttp.newCompatibleTransport()
        val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
        return YouTube.Builder(transport, jsonFactory, RequestHandler(auth))
            .setApplicationName("YouTube Vlog Thumbnail")
            .build()
    }

    suspend fun getChannelList(): List<Channel>? = suspendCoroutine { cont ->
        CoroutineScope(Dispatchers.Default).launch {
            kotlin.runCatching {
                Log.i("UPLOAD", "Performing channel list action")
                val result = getApiClient().channels().list("snippet,contentDetails,statistics")
                    .setMine(true)
                    .execute()

                Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
                cont.resume(result.items?.toList() ?: emptyList())
            }
        }
    }

    suspend fun getPlaylistVideos(playlistId: String): List<PlaylistItem>? = suspendCoroutine { cont ->
        CoroutineScope(Dispatchers.Default).launch {
            kotlin.runCatching {
                Log.i("UPLOAD", "Performing video list action")
                val result = getApiClient().playlistItems().list("snippet,contentDetails,status")
                    .setPlaylistId(playlistId)
                    .setMaxResults(10)
                    .execute()

                Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
                cont.resume(result.items?.toList() ?: emptyList())
            }
        }
    }

    private inner class RequestHandler (val auth: AccessTokenResponse) : HttpRequestInitializer,
        HttpExecuteInterceptor,
        HttpUnsuccessfulResponseHandler {

        override fun initialize(request: HttpRequest?) {
            Log.i("UPLOAD", "Initializing the request")
            if (request == null) {
                Log.i("UPLOAD", "No request, nothing to do")
                return
            }

            Log.i("UPLOAD", "Setting request handler")
            request.interceptor = this
            request.unsuccessfulResponseHandler = this
            Log.i("UPLOAD", "Initialize complete")
        }

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
                Utility.setRequiresRefresh(context)
            }
            return false
        }
    }
}
