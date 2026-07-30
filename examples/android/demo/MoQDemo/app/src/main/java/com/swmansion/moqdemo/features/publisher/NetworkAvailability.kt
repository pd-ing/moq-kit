package com.swmansion.moqdemo.features.publisher

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper

/**
 * Tracks whether the device currently has at least one network with validated
 * internet access, using a [ConnectivityManager.NetworkCallback].
 *
 * `writeFrame` on the publish path only enqueues into the transport, so it keeps
 * "succeeding" while the network is gone; this class provides the missing signal.
 * A network counts as available only when it reports [NetworkCapabilities.NET_CAPABILITY_VALIDATED],
 * so a captive-portal Wi-Fi still counts as down.
 *
 * Callbacks are delivered on the main thread. [isDown] is safe to read from any thread.
 */
class NetworkAvailability(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val validatedNetworks = mutableSetOf<Network>()

    /** True while no validated internet-capable network is present. */
    @Volatile
    var isDown: Boolean = false
        private set

    /** Called on the main thread whenever [isDown] changes. */
    var onChanged: ((Boolean) -> Unit)? = null

    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                validatedNetworks += network
            } else {
                validatedNetworks -= network
            }
            update()
        }

        override fun onLost(network: Network) {
            validatedNetworks -= network
            update()
        }
    }

    /** Starts listening. Safe to call again after [stop]. */
    fun start() {
        if (registered) return
        validatedNetworks.clear()
        val active = connectivityManager.activeNetwork
        val caps = active?.let { connectivityManager.getNetworkCapabilities(it) }
        if (active != null && caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
            validatedNetworks += active
        }
        isDown = validatedNetworks.isEmpty()
        connectivityManager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
            Handler(Looper.getMainLooper()),
        )
        registered = true
    }

    /** Stops listening and returns to the "up" state. Safe to call more than once. */
    fun stop() {
        if (registered) {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: Exception) {}
            registered = false
        }
        validatedNetworks.clear()
        isDown = false
    }

    private fun update() {
        val down = validatedNetworks.isEmpty()
        if (down != isDown) {
            isDown = down
            onChanged?.invoke(down)
        }
    }
}
