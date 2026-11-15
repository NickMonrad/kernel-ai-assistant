package com.kernel.ai.core.inference.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * True when the active connection is metered. False when it is unmetered or there is no active connection.
 * Model and voice-pack downloads share this check so both report "waiting for Wi-Fi" the same way (#1855).
 */
fun isOnMeteredNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
