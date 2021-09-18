package uk.co.cgfindies.youtubevogthumbnailcreator

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.Build
import java.util.*
import kotlin.collections.HashSet

class Profile {
    var id = ""
    var overlayUrl = ""
    var facePosition = Rect()
}

// TODO:: Pass in overlay bitmap to addProfile and generate the overlayUrl by saving the file

class ProfileManager(private val ctx: Context) {
    private val metaPrefs: SharedPreferences = ctx.getSharedPreferences("meta", Context.MODE_PRIVATE)

    private fun getProfileIds(): List<String> {
        return HashSet<String>(metaPrefs.getStringSet("profileIds", null) ?: emptySet<String>()).sorted()
    }

    fun addProfile(overlayUrl: String, facePosition: Rect) {
        val ids = getProfileIds()
        val nextId = if (ids.isEmpty()) "1" else (ids.last().toInt() + 1).toString()
        val newIds = ids.toMutableSet()
        newIds.add(nextId)
        metaPrefs.edit().putStringSet("profileIds", newIds).apply()

        val profilePrefs = ctx.getSharedPreferences(nextId, Context.MODE_PRIVATE)
        profilePrefs
            .edit()
            .putBoolean("exists", true)
            .putString("overlayUrl", overlayUrl)
            .putInt("facePositionLeft", facePosition.left)
            .putInt("facePositionTop", facePosition.top)
            .putInt("facePositionRight", facePosition.right)
            .putInt("facePositionBottom", facePosition.bottom)
            .apply()
    }

    fun removeProfile(id: String) {
        val ids = getProfileIds()
        if (ids.contains(id)) {
            val mIds = ids.toMutableSet()
            mIds.remove(id)
            metaPrefs.edit().putStringSet("profileIds", mIds).apply()

            ctx.getSharedPreferences(id, Context.MODE_PRIVATE).edit().clear().apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                ctx.deleteSharedPreferences(id)
            }
        }
    }

    fun getProfile(id: String): Profile? {
        val profilePrefs = ctx.getSharedPreferences(id, Context.MODE_PRIVATE)
        if (!profilePrefs.getBoolean("exists", false)) {
            return null
        }

        val profile = Profile()
        profile.id = id
        profile.overlayUrl = profilePrefs.getString("overlayUrl", "") ?: ""
        profile.facePosition = Rect(
            profilePrefs.getInt("facePositionLeft", 0),
            profilePrefs.getInt("facePositionTop", 0),
            profilePrefs.getInt("facePositionRight", 0),
            profilePrefs.getInt("facePositionBottom", 0)
        )
        return profile
    }

    fun getAllProfiles(): Set<Profile> {
        val ids = getProfileIds()
        val profiles = mutableSetOf<Profile>()
        for (id in ids) {
            val profile = getProfile(id)
            if (profile == null) {
                removeProfile(id)
            } else {
                profiles.add(profile)
            }
        }

        return Collections.unmodifiableSet(profiles)
    }
}