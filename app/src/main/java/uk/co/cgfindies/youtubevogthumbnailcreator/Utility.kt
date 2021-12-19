package uk.co.cgfindies.youtubevogthumbnailcreator

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.annotation.StringRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar

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

        fun getAuthentication(context: Context): AccessTokenResponse? {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            val accessToken = youtubePrefs.getString("accessToken", null)
            val expiresIn = youtubePrefs.getInt("expiresIn", -1)
            val tokenType = youtubePrefs.getString("tokenType", null)
            val scope = youtubePrefs.getString("scope", null)
            val refreshToken = youtubePrefs.getString("refreshToken", null)
            // TODO:: Also store requested time so we know when "expires in" starts from

            if (accessToken == null || expiresIn < 0 || tokenType == null || scope == null || refreshToken == null) {
                return null
            }

            return AccessTokenResponse(accessToken, expiresIn, tokenType, scope, refreshToken)
        }

        fun setAuthentication(auth: AccessTokenResponse, context: Context) {
            val youtubePrefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
            youtubePrefs.edit()
                .putString("accessToken", auth.accessToken)
                .putInt("expiresIn", auth.expiresIn)
                .putString("tokenType", auth.tokenType)
                .putString("scope", auth.scope)
                .putString("refreshToken", auth.refreshToken)
                .apply()
            // TODO:: Also store requested time so we know when "expires in" starts from
        }
    }
}
