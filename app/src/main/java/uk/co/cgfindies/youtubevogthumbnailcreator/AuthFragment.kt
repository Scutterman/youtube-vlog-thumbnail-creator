package uk.co.cgfindies.youtubevogthumbnailcreator

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.fragment.app.Fragment
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.URI

class AuthFragment : Fragment(), WebViewCompat.WebMessageListener {
    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy
    ) {
        val data = message.data ?: throw Exception("No data received from webview")
        val auth = Json.decodeFromString<AccessTokenResponse>(data)
        Utility.setAuthentication(auth, requireContext())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_auth, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val url = getString(R.string.auth_api_url)
        val uri = URI(url)
        val webView = requireActivity().findViewById<WebView>(R.id.auth_web_view)
        webView.settings.javaScriptEnabled = true
        val port = if (uri.port > 0) ":" + uri.port.toString() else ""
        WebViewCompat.addWebMessageListener(webView, "host", setOf("${ uri.scheme }://${ uri.host }$port"), this)
    }

    companion object {
        @JvmStatic
        fun newInstance(): AuthFragment {
            return AuthFragment()
        }
    }
}