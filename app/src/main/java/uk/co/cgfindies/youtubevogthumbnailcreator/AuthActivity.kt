package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.Window
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import uk.co.cgfindies.youtubevogthumbnailcreator.service.AccessTokenResponse

class AuthActivity : AppCompatActivity(), DefaultLifecycleObserver {
    private var tokenId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AppCompatActivity>.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_auth)
        setFinishOnTouchOutside(false)

        tokenId = savedInstanceState?.getString("tokenId")
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        val retry = findViewById<Button>(R.id.auth_retry)
        retry.setOnClickListener {
            doAuth()
        }

        if (tokenId == null) {
            doAuth()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("tokenId", tokenId)
        super.onSaveInstanceState(outState)
    }

    private fun showError() {
        findViewById<View>(R.id.not_a_token).visibility = View.VISIBLE
    }

    override fun onStart(owner: LifecycleOwner) {
        // app moved to foreground
        if (tokenId == null) {
            return
        }

        val url = getString(R.string.auth_api_url)
        val authRequest = JsonObjectRequest(
            Request.Method.GET, "$url/getStoredToken?tokenId=$tokenId", null,
            { response ->
                val data = response?.toString() ?: return@JsonObjectRequest
                try {
                    val auth = Json.decodeFromString<AccessTokenResponse>(data)
                    Utility.setAuthentication(auth, this)
                    tokenId = null
                    finish()
                } catch (_error: Exception) {
                    Log.e("FETCH_TOKEN", "Error while decoding the token", _error)
                    showError()
                }
            },
            { error ->
                Log.e("FETCH_TOKEN", "Could not get auth url", error)
                Utility.showMessage(this, R.string.fragment_auth_no_auth_url)
            }
        )

        val queue = Volley.newRequestQueue(this)
        queue.add(authRequest)
    }

    private fun doAuth() {
        val url = getString(R.string.auth_api_url)
        val authRequest = JsonObjectRequest(Request.Method.GET, "$url/generateAuthUrl", null,
            { response ->
                val authUrl = response?.getString("url")
                tokenId = response?.getString("tokenId")
                if (authUrl == null || tokenId == null) {
                    Log.e("FETCH_AUTH_URL", "Auth URL Response was not in the correct format ${ response?.toString() ?: "NULL" }")
                    Utility.showMessage(this, R.string.fragment_auth_no_auth_url)
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
                    startActivity(browserIntent)
                }
            },
            { error ->
                Log.e("FETCH_AUTH_URL", "Could not get auth url", error)
                Utility.showMessage(this, R.string.fragment_auth_no_auth_url)
            }
        )

        val queue = Volley.newRequestQueue(this)
        queue.add(authRequest)
    }
}
