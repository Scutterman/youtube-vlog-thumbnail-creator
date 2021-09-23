package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Build
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.*
import kotlin.collections.HashSet

class Profile {
    var id = ""
    var name = ""
    lateinit var overlay: Bitmap
    var facePosition = Rect()
}

const val PROFILE_MANAGER_DEFAULT_PROFILE_ID = "0"

class ProfileManager(private val ctx: Context) {
    private val metaPrefs: SharedPreferences = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)

    private fun getProfileIds(): List<String> {
        return HashSet<String>(metaPrefs.getStringSet("profileIds", null) ?: emptySet<String>()).sorted()
    }

    fun addProfile(name: String, overlay: Bitmap, facePosition: Rect): Profile {
        val ids = getProfileIds()
        val nextId = if (ids.isEmpty()) "1" else (ids.last().toInt() + 1).toString()
        val newIds = ids.toMutableSet()
        newIds.add(nextId)
        metaPrefs.edit().putStringSet("profileIds", newIds).apply()

        saveImage(nextId, overlay)

        val profilePrefs = ctx.getSharedPreferences(nextId, Context.MODE_PRIVATE)
        profilePrefs
            .edit()
            .putBoolean("exists", true)
            .putString("name", name)
            .putInt("facePositionLeft", facePosition.left)
            .putInt("facePositionTop", facePosition.top)
            .putInt("facePositionRight", facePosition.right)
            .putInt("facePositionBottom", facePosition.bottom)
            .apply()

        return getProfile(nextId)
    }

    fun removeProfile(id: String) {
        val ids = getProfileIds()
        if (ids.contains(id)) {
            Log.i("ProfileManager", "Removing profile id $id")
            val mIds = ids.toMutableSet()
            mIds.remove(id)
            metaPrefs.edit().putStringSet("profileIds", mIds).apply()

            Log.i("ProfileManager", "Clearing shared preferences for profile $id")
            ctx.getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Log.i("ProfileManager", "Deleting shared preferences for profile $id")
                ctx.deleteSharedPreferences(id)
            }

            deleteImage(id)
        }
    }

    fun getProfile(id: String): Profile {
        val profilePrefs = ctx.getSharedPreferences(id, Context.MODE_PRIVATE)
        if (!profilePrefs.getBoolean("exists", false)) {
            throw IllegalArgumentException("Profile \"$id\" does not exist")
        }

        val profile = Profile()
        profile.id = id
        profile.name = profilePrefs.getString("name", "") ?: ""
        profile.facePosition = Rect(
            profilePrefs.getInt("facePositionLeft", 0),
            profilePrefs.getInt("facePositionTop", 0),
            profilePrefs.getInt("facePositionRight", 0),
            profilePrefs.getInt("facePositionBottom", 0)
        )
        profile.overlay = getImage(id)
        return profile
    }

    fun getDefaultProfile(): Profile {
        val ids = getProfileIds()
        if (ids.isNotEmpty()) {
            return getProfile(ids[0])
        }

        val profile = Profile()
        profile.id = PROFILE_MANAGER_DEFAULT_PROFILE_ID
        profile.name = "Demo Profile"
        profile.facePosition = Rect(56, 207, 321, 472)
        profile.overlay = ResourcesCompat.getDrawable(ctx.resources, R.drawable.vlog_thumbnail_overlay, null)?.toBitmap()
            ?: throw IllegalStateException("Overlay resource missing")
        return profile
    }

    fun getAllProfiles(): Set<Profile> {
        val ids = getProfileIds()
        val profiles = mutableSetOf<Profile>()
        for (id in ids) {
            try {
                val profile = getProfile(id)
                profiles.add(profile)
            } catch (e: java.lang.IllegalArgumentException) {
                Log.i("ProfileManager", "Profile $id doesn't exist, removing it from profile id list")
                removeProfile(id)
            }
        }

        return Collections.unmodifiableSet(profiles)
    }

    private fun saveImage(id: String, bitmap: Bitmap): String {
        val filename = "overlay_$id"
        ctx.openFileOutput(filename, Context.MODE_PRIVATE).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return filename
    }

    private fun getImage(id: String): Bitmap {
        val filename = "overlay_$id"
        val reader = ctx.openFileInput(filename).buffered()
        return BitmapFactory.decodeStream(reader)
    }

    private fun deleteImage(id: String) {
        val file = File(ctx.filesDir, "overlay_$id")
        file.delete()
    }
}
