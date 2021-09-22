package uk.co.cgfindies.youtubevogthumbnailcreator

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.ListView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout

const val TITLE_LINE_LENGTH = 13

class MainActivity : AppCompatActivity() {

    private lateinit var drawer: DrawerLayout

    private lateinit var profileManager: ProfileManager
    private lateinit var currentProfile: Profile
    private lateinit var profileAdapter: ProfileAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        drawer = findViewById(R.id.drawer_layout)

        /**
         * TODO::
         * - Add UI for adding a profile instead of just adding a default profile
         */
        profileManager = ProfileManager(this)
        currentProfile = profileManager.getDefaultProfile()

        populateProfileList()

        findViewById<Button>(R.id.btn_add_profile).setOnClickListener {
            val bitmap = ResourcesCompat.getDrawable(resources, R.drawable.vlog_thumbnail_overlay, null)?.toBitmap()
                ?: throw IllegalStateException("Overlay resource missing")

            val newProfile = profileManager.addProfile("Demo Foo", bitmap, Rect(56, 207, 321, 472))
            profileAdapter.add(newProfile)
            profileAdapter.notifyDataSetInvalidated()
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .setReorderingAllowed(true)
                .add(R.id.fragment_container_view, ThumbnailFragment.newInstance(currentProfile.id), "MAIN_FRAGMENT")
                .commit()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id: Int = item.itemId
        Log.i("MAIN", "Click on option ${item.itemId} and we want ${android.R.id.home}")
        return if (id == android.R.id.home) {
            Log.i("Main", "Button click - ${ drawer.isDrawerOpen(GravityCompat.START) }")
            if (drawer.isDrawerOpen(GravityCompat.START)) {
                Log.i("Main", "Closing")
                drawer.closeDrawer(GravityCompat.START)
            } else {
                Log.i("Main", "Opening")
                drawer.openDrawer(GravityCompat.START)
            }
            true
        } else super.onOptionsItemSelected(item)
    }

    private fun populateProfileList() {
        val list = findViewById<ListView>(R.id.profile_list)
        val profiles = profileManager.getAllProfiles().toMutableList()

        profileAdapter = ProfileAdapter(this, profiles)
        profileAdapter.itemDeletedListener = {
            position ->
            val profile = profileAdapter.getItem(position) ?: throw IllegalStateException("Could not profile item clicked on")

            profileManager.removeProfile(profile.id)
            profileAdapter.remove(profile)
            profileAdapter.notifyDataSetChanged()
        }

        list.adapter = profileAdapter
        list.setOnItemClickListener { _, _, position, _ ->
            currentProfile = profileAdapter.getItem(position) ?: throw IllegalStateException("Could not profile item clicked on")
            val fragment = supportFragmentManager.findFragmentByTag("MAIN_FRAGMENT")
            if (fragment is ThumbnailFragment) {
                fragment.profileChanged(currentProfile)
            }
        }

        Log.i("ProfileAdapter", "Done populating profile list")
    }
}
