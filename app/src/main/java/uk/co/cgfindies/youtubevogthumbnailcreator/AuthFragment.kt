package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json


class AuthFragment : Fragment(), DefaultLifecycleObserver {
    private var tokenId: String? = null

    private fun onAuthCompleted() {
        val data = requireActivity().findViewById<EditText>(R.id.auth_token).text.toString()
        try {
            val auth = Json.decodeFromString<AccessTokenResponse>(data)
            Utility.setAuthentication(auth, requireContext())
        } catch (_error: Exception) {
            showError()
        }
    }

    private fun showError() {
        requireActivity().findViewById<View>(R.id.not_a_token).visibility = VISIBLE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super<Fragment>.onCreate(savedInstanceState)
        tokenId = savedInstanceState?.getString("tokenId")
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_auth, container, false)
    }

    override fun onStart(owner: LifecycleOwner) {
        // app moved to foreground
        if (tokenId == null) {
            return
        }
        val url = getString(R.string.auth_api_url)
        val authRequest = JsonObjectRequest(Request.Method.GET, "$url/getStoredToken?tokenId=$tokenId", null,
            { response ->
                val data = response?.toString() ?: return@JsonObjectRequest
                try {
                    val auth = Json.decodeFromString<AccessTokenResponse>(data)
                    Utility.setAuthentication(auth, requireContext())
                    tokenId = null
                } catch (_error: Exception) {
                    Log.e("FETCH_TOKEN", "Error while decoding the token", e)
                    showError()
                }
            },
            { error ->
                Log.e("FETCH_TOKEN", "Could not get auth url", error)
                Utility.showMessage(requireActivity(), R.string.fragment_auth_no_auth_url)
            }
        )

        val queue = Volley.newRequestQueue(requireContext())
        queue.add(authRequest)
    }

    override fun onStop(owner: LifecycleOwner) {
        // app moved to background, no action required
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tokenId", tokenId)
        super.onSaveInstanceState(outState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().findViewById<Button>(R.id.auth_complete_authentication).setOnClickListener { onAuthCompleted() }

        val url = getString(R.string.auth_api_url)
        val authRequest = JsonObjectRequest(Request.Method.GET, "$url/generateAuthUrl", null,
            { response ->
                val authUrl = response?.getString("url")
                tokenId = response?.getString("tokenId")
                if (authUrl == null || tokenId == null) {
                    Log.e("FETCH_AUTH_URL", "Auth URL Response was not in the correct format ${ response?.toString() ?: "NULL" }")
                    Utility.showMessage(requireActivity(), R.string.fragment_auth_no_auth_url)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                    startActivity(browserIntent)
                }
            },
            { error ->
                Log.e("FETCH_AUTH_URL", "Could not get auth url", error)
                Utility.showMessage(requireActivity(), R.string.fragment_auth_no_auth_url)
            }
        )

        val queue = Volley.newRequestQueue(requireContext())
        queue.add(authRequest)
    }

    companion object {
        @JvmStatic
        fun newInstance(): AuthFragment {
            return AuthFragment()
        }
    }
}
