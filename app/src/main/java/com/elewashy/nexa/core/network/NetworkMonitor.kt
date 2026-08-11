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
 * Exposes:
 *  - [isOnline] for one-shot synchronous checks
 *  - [hasAnyNetwork] to distinguish "no radio network at all" from "network
 *    present but not (yet) validated" (e.g. captive portals)
 *  - [online] as a cold [Flow] that emits on every connectivity transition;
 *    ideal for UI states that should react to the network coming and going.
 *
 * "Online" requires [NetworkCapabilities.NET_CAPABILITY_VALIDATED]: a Wi-Fi
 * network behind a captive portal has transport but no actual internet, and
 * must not read as online.
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

    /** True when any active network exists, validated or not. */
    fun hasAnyNetwork(): Boolean
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
        // VALIDATED filters out captive portals and networks that claim
        // internet access but cannot actually reach it.
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun hasAnyNetwork(): Boolean = connectivityManager.activeNetwork != null

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
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        connectivityManager.registerNetworkCallback(request, callback)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
