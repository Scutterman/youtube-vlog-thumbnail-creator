package uk.co.cgfindies.youtubevogthumbnailcreator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.android.volley.Request
import com.android.volley.toolbox.JsonObjectRequest
import com.android.volley.toolbox.Volley
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject

class Utility {
    companion object {
        fun showMessage(activity: Activity, @StringRes messageId: Int) {
            val view = activity.findViewById<View>(android.R.id.content)
            Snackbar.make(view, messageId, Snackbar.LENGTH_LONG).show()
        }

        fun hasPermission(context: Context, permission: String): Boolean {
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

        fun getPermission(activity: Activity, contract: ActivityResultLauncher<String>, permission: String, message: String) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    permission
                )
            ) {
                val alertBuilder = AlertDialog.Builder(activity)
                alertBuilder.setCancelable(true)
                alertBuilder.setMessage(message)
                alertBuilder.setPositiveButton(
                    android.R.string.ok
                ) { _, _ ->
                    contract.launch(permission)
                }
                alertBuilder.show()
            } else {
                contract.launch(permission)
            }
        }

        fun getAuthentication(context: Context, onComplete: (accessTokenResponse: AccessTokenResponse?) -> Unit) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            val accessToken = youtubePrefs.getString("accessToken", null)
            val expiryDate = youtubePrefs.getLong("expiryDate", -1)
            val tokenType = youtubePrefs.getString("tokenType", null)
            val scope = youtubePrefs.getString("scope", null)
            val refreshToken = youtubePrefs.getString("refreshToken", null)

            if (accessToken == null || expiryDate < 0 || tokenType == null || scope == null || refreshToken == null) {
                Log.i("GET_AUTHENTICATION", "One or more expected parameters is null, cannot construct AccessTokenResponse")
                onComplete(null)
                return
            }

            val time = System.currentTimeMillis()
            Log.i("GET_AUTHENTICATION", "Access token expires at $expiryDate, current time is $time")
            if (expiryDate >= time) {
                Log.i("GET_AUTHENTICATION", "Token still valid, returning AccessTokenResponse")
                onComplete(
                    AccessTokenResponse(
                        accessToken,
                        expiryDate,
                        tokenType,
                        scope,
                        refreshToken
                    )
                )
                return
            }

            val url = context.getString(R.string.auth_api_url)
            val body =  JSONObject(mapOf((Pair("refreshToken", refreshToken))))

            Log.i("GET_AUTHENTICATION", "Making request to refresh the access token")
            val authRequest = JsonObjectRequest(
                Request.Method.POST, "$url/refreshToken", body,
                { response ->
                    Log.i("GET_AUTHENTICATION", "Got response")
                    val refreshedAccessToken = response?.getString("token")
                    if (refreshedAccessToken == null) {
                        Log.i("GET_AUTHENTICATION", "New access token is null")
                        onComplete(null)
                    } else {
                        youtubePrefs.edit()
                            .putString("accessToken", refreshedAccessToken)
                            .apply()

                        Log.i("GET_AUTHENTICATION", "Stored new access token and returning AccessTokenResponse object")
                        onComplete(AccessTokenResponse(refreshedAccessToken, expiryDate, tokenType, scope, refreshToken))
                    }
                },
                { error ->
                    Log.e("REFRESH_ACCESS_TOKEN", "Could not refresh the access token", error)
                    onComplete(null)
                }
            )

            val queue = Volley.newRequestQueue(context)
            queue.add(authRequest)
        }

        fun setAuthentication(auth: AccessTokenResponse, context: Context) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            youtubePrefs.edit()
                .putString("accessToken", auth.access_token)
                .putLong("expiryDate", auth.expiry_date)
                .putString("tokenType", auth.token_type)
                .putString("scope", auth.scope)
                .putString("refreshToken", auth.refresh_token)
                .apply()
        }
    }
}
