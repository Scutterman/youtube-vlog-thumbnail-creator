package uk.co.cgfindies.youtubevogthumbnailcreator

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

class NetworkMonitor private constructor(context: Context, unmeteredOnly: Boolean = true) : ConnectivityManager.NetworkCallback() {

    interface ConnectionObserver {
        fun connectionAvailabilityChanged(isConnected: Boolean)
    }

    companion object {

        @Volatile private var INSTANCE: NetworkMonitor? = null
        @Volatile private var UNMETERED_INSTANCE: NetworkMonitor? = null

        fun getInstance(context: Context): NetworkMonitor =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NetworkMonitor(context).also { INSTANCE = it }
            }

        fun getUnmeteredInstance(context: Context): NetworkMonitor =
            UNMETERED_INSTANCE ?: synchronized(this) {
                UNMETERED_INSTANCE ?: NetworkMonitor(context, true).also { UNMETERED_INSTANCE = it }
            }
    }

    private val networks: MutableList<Network> = mutableListOf()
    private val connectionObservers: MutableList<WeakReference<ConnectionObserver>> = mutableListOf()

     init {
        val capabilities = if (unmeteredOnly) NetworkCapabilities.NET_CAPABILITY_NOT_METERED else NetworkCapabilities.NET_CAPABILITY_INTERNET
        val networkFilter = NetworkRequest.Builder()
            .addCapability(capabilities)
            .build()

        val manager = context.applicationContext.getSystemService(Activity.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager.registerNetworkCallback(networkFilter, this)
    }

    val isConnected: Boolean
        get() {
            return networks.isNotEmpty()
        }

    override fun onAvailable(network: Network) {
        super.onAvailable(network)
        Log.i("NETWORK_MONITOR", "A network became available")
        val before = isConnected
        networks.add(network)
        val after = isConnected
        if (before != after) { notifyConnectionAvailabilityChange() }
    }

    override fun onLost(network: Network) {
        super.onLost(network)
        Log.i("NETWORK_MONITOR", "A network was lost")
        val before = isConnected
        networks.remove(network)
        val after = isConnected
        if (before != after) { notifyConnectionAvailabilityChange() }
    }

    fun registerConnectionObserver(observer: ConnectionObserver) {
        connectionObservers.add(WeakReference(observer))
    }

    fun unregisterConnectionObserver(observer: ConnectionObserver) {
        connectionObservers.remove(WeakReference(observer))
    }

    private fun notifyConnectionAvailabilityChange() {
        connectionObservers.forEach { observer ->
            CoroutineScope(Dispatchers.Default).launch {
                observer.get()?.connectionAvailabilityChanged(isConnected)
            }
        }
    }
}
