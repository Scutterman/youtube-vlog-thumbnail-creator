package uk.co.cgfindies.youtubevogthumbnailcreator

import android.os.Bundle
import android.view.Window
import androidx.appcompat.app.AppCompatActivity

class AuthActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.activity_auth)
        setFinishOnTouchOutside(false)

        if (savedInstanceState == null) {
            val container = R.id.fragment_container_view
            val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
            transaction.add(container, AuthFragment.newInstance() { finish() }, "ADD_AUTH_FRAGMENT")
            transaction.commit()
        }
    }
}

