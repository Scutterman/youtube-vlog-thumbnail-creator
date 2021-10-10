package uk.co.cgfindies.youtubevogthumbnailcreator

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

class NetworkMonitor private constructor(context: Context) : ConnectivityManager.NetworkCallback() {
    companion object {

        @Volatile private var INSTANCE: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkMonitor(context).also { INSTANCE = it }
            }
    }

    private val networks: MutableList<Network> = mutableListOf()

     init {
        val networkFilter = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        (context.applicationContext.getSystemService(Activity.CONNECTIVITY_SERVICE) as ConnectivityManager).registerNetworkCallback(networkFilter, this)
    }

    val isConnected: Boolean
        get() {
            return networks.isNotEmpty()
        }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.i("NETWORK_MONITOR", "available $network")
        networks.add(network)
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.i("NETWORK_MONITOR", "lost $network")
        networks.remove(network)
    }

}