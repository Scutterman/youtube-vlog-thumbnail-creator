package uk.co.cgfindies.youtubevogthumbnailcreator

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.GooglePlayServicesAvailabilityIOException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.json.JsonFactory
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.ExponentialBackOff
import com.google.api.services.youtube.YouTube
import com.google.api.services.youtube.YouTubeScopes
import com.google.api.services.youtube.model.Channel
import kotlinx.coroutines.DelicateCoroutinesApi

private const val PREF_ACCOUNT_NAME = "accountName"

class ChooseAccountActivityResultContract: ActivityResultContract<GoogleAccountCredential, String?>() {
    override fun createIntent(context: Context, input: GoogleAccountCredential): Intent {
        return input.newChooseAccountIntent()
    }

    override fun parseResult(resultCode: Int, data: Intent?): String? {
        return if (resultCode == Activity.RESULT_OK && data != null && data.extras != null) {
            data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        } else null
    }
}

class RequestAuthorizationActivityResultContract: ActivityResultContract<UserRecoverableAuthIOException, Boolean>() {
    override fun createIntent(context: Context, input: UserRecoverableAuthIOException): Intent {
        return input.intent
    }

    override fun parseResult(resultCode: Int, data: Intent?): Boolean {
        return resultCode == Activity.RESULT_OK
    }
}

/**
 * TODO::At least some of this should be in a service so we don't have to deal with it getting destroyed
 */
@DelicateCoroutinesApi
open class YoutubeBase: Fragment() {
    private lateinit var googleCredentialManager: GoogleAccountCredential

    private lateinit var networkMonitor: NetworkMonitor

    private val newAccountContract = registerForActivityResult(ChooseAccountActivityResultContract()) { accountName ->
        if (accountName != null) {
            val settings = requireActivity().getPreferences(Activity.MODE_PRIVATE)
            val editor = settings.edit()
            editor.putString(PREF_ACCOUNT_NAME, accountName)
            editor.apply()
            googleCredentialManager.selectedAccountName = accountName
            resultsFromApi
        }
    }

    private val requestAuthorizationContract = registerForActivityResult(RequestAuthorizationActivityResultContract()) { isAuthorized ->
        if (isAuthorized) {
            resultsFromApi
        }
    }

    private val requestAccountsPermissionContract = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            chooseAccount()
        }
    }

    private var action: (() -> Unit)? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        networkMonitor = NetworkMonitor.getInstance(requireContext())

        googleCredentialManager = GoogleAccountCredential.usingOAuth2(
            activity,
            listOf(YouTubeScopes.YOUTUBE_READONLY, YouTubeScopes.YOUTUBE_UPLOAD)
        ).setBackOff(ExponentialBackOff())
    }

    fun getChannelList(onResult: (List<Channel>?) -> Unit) {
        action = {
            MakeRequestTask<List<Channel>>(googleCredentialManager, { apiClient ->
                Log.i("UPLOAD", "data from api")
                val result = apiClient.channels().list("snippet,contentDetails,statistics")
                    .setMine(true)
                    .execute()

                Log.i("UPLOAD", "Got channels ${result.items?.size ?: 0}")
                return@MakeRequestTask result.items?.toList() ?: emptyList()
            }, onResult).execute()
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
            if (!isGooglePlayServicesAvailable) {
                Log.i("UPLOAD", "No Play Services")
                acquireGooglePlayServices()
            } else if (googleCredentialManager.selectedAccountName == null) {
                Log.i("UPLOAD", "Choosing account")
                chooseAccount()
            } else if (!networkMonitor.isConnected) {
                Log.i("UPLOAD", "No Network")
                Utility.showMessage(requireActivity(), R.string.network_unavailable)
            } else {
                Log.i("UPLOAD", "Make request task")
                action?.invoke()
            }
        }

    /**
     * Attempts to set the account used with the API credentials. If an account
     * name was previously saved it will use that one; otherwise an account
     * picker dialog will be shown to the user. Note that the setting the
     * account to use with the credentials object requires the app to have the
     * GET_ACCOUNTS permission, which is requested here if it is not already
     * present. The AfterPermissionGranted annotation indicates that this
     * function will be rerun automatically whenever the GET_ACCOUNTS permission
     * is granted.
     */
    private fun chooseAccount() {
        if (Utility.hasPermission(requireContext(), Manifest.permission.GET_ACCOUNTS)) {
            val accountName = requireActivity().getPreferences(Activity.MODE_PRIVATE)
                .getString(PREF_ACCOUNT_NAME, null)
            if (accountName != null) {
                googleCredentialManager.selectedAccountName = accountName
                resultsFromApi
            } else {
                newAccountContract.launch(googleCredentialManager)
            }
        } else {
            Utility.getPermission(requireActivity(), requestAccountsPermissionContract, Manifest.permission.GET_ACCOUNTS, getString(R.string.request_accounts_permission_message))
        }
    }

    /**
     * Check that Google Play services APK is installed and up to date.
     * @return true if Google Play Services is available and up to
     * date on this device; false otherwise.
     */
    private val isGooglePlayServicesAvailable: Boolean
        get() {
            val apiAvailability = GoogleApiAvailability.getInstance()
            val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(requireContext())
            return connectionStatusCode == ConnectionResult.SUCCESS
        }

    /**
     * Attempt to resolve a missing, out-of-date, invalid or disabled Google
     * Play Services installation via a user dialog, if possible.
     */
    private fun acquireGooglePlayServices() {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val connectionStatusCode = apiAvailability.isGooglePlayServicesAvailable(requireContext())
        if (apiAvailability.isUserResolvableError(connectionStatusCode)) {
            showGooglePlayServicesAvailabilityErrorDialog(connectionStatusCode)
        }
    }

    /**
     * Display an error dialog showing that Google Play Services is missing
     * or out of date.
     * @param connectionStatusCode code describing the presence (or lack of)
     * Google Play Services on this device.
     */
    fun showGooglePlayServicesAvailabilityErrorDialog(
        connectionStatusCode: Int
    ) {
        val apiAvailability = GoogleApiAvailability.getInstance()
        val dialog = apiAvailability.getErrorDialog(requireActivity(), connectionStatusCode,0) {
            Utility.showMessage(requireActivity(), R.string.requires_google_play_services)
        }

        dialog?.show()
    }

    /**
     * An asynchronous task that handles the YouTube Data API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private inner class MakeRequestTask<Result>
        constructor(credential: GoogleAccountCredential, private val fetchData: (apiClient: YouTube) -> Result, private val onResult: (result: Result?) -> Unit) :
            CoroutinesAsyncTask<Void?, Void?, Result>("MakeYoutubeRequest") {

        private val apiClient: YouTube

        init {
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
            apiClient = YouTube.Builder(transport, jsonFactory, credential)
                .setApplicationName("YouTube Vlog Thumbnail")
                .build()
        }

        /**
         * Background task to call YouTube Data API.
         * @param params no parameters needed for this task.
         */
        override fun doInBackground(vararg params: Void?): Result? {
            return try {
                Log.i("UPLOAD", "Trying getting data")
                fetchData(apiClient)
            } catch (e: Exception) {
                Log.e("UPLOAD", "do background error", e)
                cancel(true, e)
                null
            }
        }

        override fun onPreExecute() {
            Log.i("UPLOAD", "Pre execute")
        }

        override fun onPostExecute(result: Result?) {
            Log.i("UPLOAD", "Post execute")
            onResult(result)

        }

        override fun onCancelled(e: java.lang.Exception?) {
            Log.i("UPLOAD", "Cancelled")
            if (e != null) {
                when (e) {
                    is GooglePlayServicesAvailabilityIOException -> {
                        showGooglePlayServicesAvailabilityErrorDialog(e.connectionStatusCode
                        )
                    }
                    is UserRecoverableAuthIOException -> {
                        requestAuthorizationContract.launch(e)
                    }
                    else -> {
                        Log.e("UPLOAD", "Error while uploading", e)
                        Utility.showMessage(requireActivity(), R.string.error_uploading)
                    }
                }
            } else {
                Utility.showMessage(requireActivity(), R.string.upload_cancelled)
            }
        }
    }
}