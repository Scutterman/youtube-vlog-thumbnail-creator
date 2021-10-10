 package uk.co.cgfindies.youtubevogthumbnailcreator

import android.Manifest
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
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
import kotlinx.coroutines.DelicateCoroutinesApi
import java.io.IOException
import java.util.*

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
 * A simple [Fragment] subclass.
 * Use the [UploadFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 * TODO::At least some of this should be in a service so we don't have to deal with it getting destroyed
 */
 @DelicateCoroutinesApi
class UploadFragment : Fragment() {
    private var mCredential: GoogleAccountCredential? = null
    private val newAccountContract = registerForActivityResult(ChooseAccountActivityResultContract()) { accountName ->
        if (accountName != null) {
            val settings = requireActivity().getPreferences(Activity.MODE_PRIVATE)
            val editor = settings.edit()
            editor.putString(PREF_ACCOUNT_NAME, accountName)
            editor.apply()
            mCredential!!.selectedAccountName = accountName
            resultsFromApi
        }
    }

     private val requestAuthorizationContract = registerForActivityResult(RequestAuthorizationActivityResultContract()) { isAuthorized ->
         if (isAuthorized) {
             resultsFromApi
         }
     }

     private val requestAccountsPermissionContract = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
         if (granted) {
             chooseAccount()
         }
     }

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
            clearOutputText()
            resultsFromApi
            button.isEnabled = true
        }

        mCredential = GoogleAccountCredential.usingOAuth2(
            requireActivity().applicationContext, listOf(YouTubeScopes.YOUTUBE_READONLY, YouTubeScopes.YOUTUBE_UPLOAD)
        )
        .setBackOff(ExponentialBackOff())
    }

    private fun clearOutputText() {
        requireView().findViewById<TextView>(R.id.upload_output).text = ""
    }

    private fun setOutputText(resource: Int) {
        setOutputText(getString(resource))
    }

    private fun setOutputText(text: String) {
        val view = requireView().findViewById<TextView>(R.id.upload_output)
        view.text = text
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
            } else if (mCredential?.selectedAccountName == null) {
                Log.i("UPLOAD", "Choosing account")
                chooseAccount()
            } else if (!isDeviceOnline) {
                Log.i("UPLOAD", "No Network")
                setOutputText(R.string.network_unavailable)
            } else {
                Log.i("UPLOAD", "Make request task")
                MakeRequestTask(mCredential).execute()
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
            if (accountName != null && mCredential != null) {
                mCredential?.selectedAccountName = accountName
                resultsFromApi
            } else {
                newAccountContract.launch(mCredential)
            }
        } else {
            Utility.getPermission(requireActivity(), requestAccountsPermissionContract, Manifest.permission.GET_ACCOUNTS, getString(R.string.request_accounts_permission_message))
        }
    }

    /**
     * Checks whether the device currently has a network connection.
     * @return true if the device has a network connection, false otherwise.
     */
    private val isDeviceOnline: Boolean
        get() {
            val connMgr = requireContext().getSystemService(Activity.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkInfo = connMgr.activeNetworkInfo
            return networkInfo != null && networkInfo.isConnected
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
        val dialog = apiAvailability.getErrorDialog(this@UploadFragment, connectionStatusCode,0) {
            Utility.showMessage(requireActivity(), R.string.requires_google_play_services)
        }

        dialog?.show()
    }

    /**
     * An asynchronous task that handles the YouTube Data API call.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private inner class MakeRequestTask constructor(credential: GoogleAccountCredential?) :
        CoroutinesAsyncTask<Void?, Void?, List<String?>?>("MakeYoutubeRequest") {
        private var mService: YouTube? = null
        private var mLastError: Exception? = null

        /**
         * Background task to call YouTube Data API.
         * @param params no parameters needed for this task.
         */
        override fun doInBackground(vararg params: Void?): List<String?>? {
            return try {
                Log.i("UPLOAD", "Trying getting data")
                dataFromApi
            } catch (e: Exception) {
                Log.e("UPLOAD", "do background error", e)
                mLastError = e
                cancel(true)
                null
            }
        }// Get a list of up to 10 files.

        /**
         * Fetch information about the "GoogleDevelopers" YouTube channel.
         * @return List of Strings containing information about the channel.
         * @throws IOException
         */
        @get:Throws(IOException::class)
        private val dataFromApi: List<String?>
            get() {
                Log.i("UPLOAD", "data from api")
                // Get a list of up to 10 files.
                val channelInfo: MutableList<String?> = ArrayList()
                val result = mService!!.channels().list("snippet,contentDetails,statistics")
                    .setMine(true)
                    .execute()

                val channels = result.items
                if (channels != null) {
                    Log.i("UPLOAD", "Got channels ${ channels.size }")
                    val channel = channels[0]
                    channelInfo.add(
                        "This channel's ID is " + channel.id + ". " +
                                "Its title is '" + channel.snippet.title + ", " +
                                "and it has " + channel.statistics.viewCount + " views."
                    )
                } else {
                    Log.i("UPLOAD", "Channels is null")
                }

                Log.i("UPLOAD", "returning data")
                return channelInfo
            }

        override fun onPreExecute() {
            Log.i("UPLOAD", "Pre execute")
            clearOutputText()
        }

        override fun onPostExecute(result: List<String?>?) {
            Log.i("UPLOAD", "Post execute")
            if (result == null || result.isEmpty()) {
                Log.i("UPLOAD", "No results")
                setOutputText(R.string.no_results)
            } else {
                Log.i("UPLOAD", "Got data")
                val resultMutable = result.toMutableList()
                resultMutable.add(0, "Data retrieved using the YouTube Data API:")
                setOutputText(TextUtils.join("\n", resultMutable))
            }
        }

        override fun onCancelled(result: List<String?>?) {
            Log.i("UPLOAD", "Cancelled")
            if (mLastError != null) {
                when (mLastError) {
                    is GooglePlayServicesAvailabilityIOException -> {
                        showGooglePlayServicesAvailabilityErrorDialog(
                            (mLastError as GooglePlayServicesAvailabilityIOException)
                                .connectionStatusCode
                        )
                    }
                    is UserRecoverableAuthIOException -> {
                        requestAuthorizationContract.launch(mLastError as UserRecoverableAuthIOException)
                    }
                    else -> {
                        Log.e("UPLOAD", "Error while uploading", mLastError)
                        setOutputText(R.string.error_uploading)
                    }
                }
            } else {
                setOutputText(R.string.upload_cancelled)
            }
        }

        init {
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory: JsonFactory = JacksonFactory.getDefaultInstance()
            mService = YouTube.Builder(
                transport, jsonFactory, credential
            )
                .setApplicationName("YouTube Data API Android Quickstart")
                .build()
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