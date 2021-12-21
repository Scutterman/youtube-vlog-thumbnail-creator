package uk.co.cgfindies.youtubevogthumbnailcreator

import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatActivity

class AuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth)
        setFinishOnTouchOutside(false)
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        if (savedInstanceState == null) {
            val container = R.id.fragment_container_view
            val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            transaction.add(container, AuthFragment.newInstance(), "ADD_AUTH_FRAGMENT")
            transaction.commit()
        }
    }
}

