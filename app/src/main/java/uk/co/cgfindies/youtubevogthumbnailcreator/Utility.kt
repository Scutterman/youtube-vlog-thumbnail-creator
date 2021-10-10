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
    }
}