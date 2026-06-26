package com.elewashy.nexa.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reactive and imperative access to the device's internet connectivity state.
 *
 * Replaces the free-function `checkForInternet(context)` in `util/NetworkUtils.kt`
 * (kept during the phased migration; will be removed once all call-sites migrate).
 *
 * Exposes:
 *  - [isOnline] for one-shot synchronous checks (same semantics as the legacy helper)
 *  - [online] as a cold [Flow] that emits on every connectivity transition; ideal for
 *    UI states that should react to the network coming and going.
 *
 * The [Flow] uses a [ConnectivityManager.NetworkCallback] with a capabilities-filtered
 * [NetworkRequest] so offline→online transitions are observed precisely, without
 * polling.
 */
interface NetworkMonitor {
    /** Cold flow of connectivity state. Emits the current value on subscription. */
    val online: Flow<Boolean>

    /** One-shot synchronous check. Prefer [online] for lifecycle-aware UI. */
    fun isOnline(): Boolean
}

@Singleton
class ConnectivityNetworkMonitor @Inject constructor(
    @param:ApplicationContext private val context: Context
) : NetworkMonitor {

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun isOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    override val online: Flow<Boolean> = channelFlow {
        // Emit current state immediately so subscribers don't have to wait for a
        // network event before they know whether they're online.
        trySend(isOnline())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                // Another network may still be up (e.g. Wi-Fi lost but cellular is
                // active). Recompute rather than emit `false` unconditionally.
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                trySend(isOnline())
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/**
 * Convenience bridge for call-sites that need an immediate connectivity check.
 *
 * Prefer injecting [NetworkMonitor] over calling this directly.
 */
@Suppress("DEPRECATION")
fun checkForInternet(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
}
