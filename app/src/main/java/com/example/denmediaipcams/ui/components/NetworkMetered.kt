package com.example.denmediaipcams.ui.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/** Разовая проверка: активное подключение лимитное (для фоновых задач вне Compose) */
fun isNetworkMetered(context: Context): Boolean =
    (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

/**
 * Работает ли устройство через лимитное подключение (мобильный интернет с ограничением трафика,
 * точка доступа с пометкой «лимитное»). Значение обновляется при смене сети.
 */
@Composable
fun rememberIsNetworkMetered(): State<Boolean> {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    val metered = remember { mutableStateOf(manager.isActiveNetworkMetered) }
    DisposableEffect(manager) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                metered.value = manager.isActiveNetworkMetered
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                metered.value = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }

            override fun onLost(network: Network) {
                metered.value = manager.isActiveNetworkMetered
            }
        }
        manager.registerDefaultNetworkCallback(callback)
        onDispose { manager.unregisterNetworkCallback(callback) }
    }
    return metered
}
