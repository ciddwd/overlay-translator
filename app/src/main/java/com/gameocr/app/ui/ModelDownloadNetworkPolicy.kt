package com.gameocr.app.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal enum class ModelDownloadNetworkKind {
    WIFI,
    CELLULAR,
    UNKNOWN,
}

internal enum class ModelDownloadNetworkWarning {
    CELLULAR,
    UNKNOWN,
}

internal fun classifyModelDownloadNetwork(
    hasWifi: Boolean,
    hasEthernet: Boolean,
    hasCellular: Boolean,
): ModelDownloadNetworkKind = when {
    hasWifi || hasEthernet -> ModelDownloadNetworkKind.WIFI
    hasCellular -> ModelDownloadNetworkKind.CELLULAR
    else -> ModelDownloadNetworkKind.UNKNOWN
}

internal fun modelDownloadNetworkWarningFor(
    kind: ModelDownloadNetworkKind,
): ModelDownloadNetworkWarning? = when (kind) {
    ModelDownloadNetworkKind.WIFI -> null
    ModelDownloadNetworkKind.CELLULAR -> ModelDownloadNetworkWarning.CELLULAR
    ModelDownloadNetworkKind.UNKNOWN -> ModelDownloadNetworkWarning.UNKNOWN
}

internal fun currentModelDownloadNetworkKind(context: Context): ModelDownloadNetworkKind {
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        ?: return ModelDownloadNetworkKind.UNKNOWN
    val network = connectivityManager.activeNetwork ?: return ModelDownloadNetworkKind.UNKNOWN
    val capabilities = connectivityManager.getNetworkCapabilities(network)
        ?: return ModelDownloadNetworkKind.UNKNOWN
    return classifyModelDownloadNetwork(
        hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        hasEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        hasCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
    )
}
