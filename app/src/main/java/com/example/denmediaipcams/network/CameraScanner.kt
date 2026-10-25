package com.example.denmediaipcams.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Камера, найденная в локальной сети */
data class DiscoveredCamera(
    val host: String,
    val rtspPort: Int?,
    val onvifPort: Int?,
    val name: String?,
    val hardware: String?
)

/**
 * Поиск IP-камер в локальной сети:
 * 1) ONVIF WS-Discovery — камеры сами откликаются на multicast-запрос (с портом ONVIF, названием и моделью);
 * 2) проверка RTSP-портов (554, 8554) у всех адресов подсети /24 — находит камеры без ONVIF.
 */
object CameraScanner {

    private const val TAG = "CameraScanner"
    private const val WS_DISCOVERY_ADDRESS = "239.255.255.250"
    private const val WS_DISCOVERY_PORT = 3702
    private const val WS_DISCOVERY_WAIT_MS = 3000
    private const val PORT_PROBE_TIMEOUT_MS = 400
    private const val MAX_PARALLEL_PROBES = 64
    private val RTSP_PORTS = listOf(554, 8554)

    suspend fun scan(context: Context, onProgress: (String) -> Unit): List<DiscoveredCamera> = coroutineScope {
        val localAddress = findLocalIpv4(context)
            ?: throw IllegalStateException("Нет подключения к локальной сети (Wi-Fi или Ethernet)")
        val subnetPrefix = localAddress.hostAddress!!.substringBeforeLast(".")

        // 1. ONVIF WS-Discovery
        onProgress("ONVIF: поиск камер в сети $subnetPrefix.0/24...")
        val onvifDevices = withContext(Dispatchers.IO) { wsDiscovery(context) }
            .associateBy { it.host }

        // 2. Проверка RTSP-портов всех адресов подсети
        val hosts = (1..254).map { "$subnetPrefix.$it" }.filter { it != localAddress.hostAddress }
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        val checked = AtomicInteger(0)
        val total = hosts.size * RTSP_PORTS.size
        onProgress("Проверка RTSP-портов: 0/${hosts.size} адресов")

        val rtspOpen = hosts.flatMap { host ->
            RTSP_PORTS.map { port ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val open = isPortOpen(host, port)
                        val done = checked.incrementAndGet()
                        if (done % 32 == 0 || done == total) {
                            withContext(Dispatchers.Main) {
                                onProgress("Проверка RTSP-портов: ${done / RTSP_PORTS.size}/${hosts.size} адресов")
                            }
                        }
                        if (open) host to port else null
                    }
                }
            }
        }.awaitAll().filterNotNull()
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, ports) -> ports.minOf { it } }

        // Объединяем результаты: ONVIF даёт название и порт ONVIF, проверка портов — RTSP
        (onvifDevices.keys + rtspOpen.keys).map { host ->
            val onvif = onvifDevices[host]
            DiscoveredCamera(
                host = host,
                rtspPort = rtspOpen[host],
                onvifPort = onvif?.onvifPort,
                name = onvif?.name,
                hardware = onvif?.hardware
            )
        }.sortedBy { it.host.substringAfterLast(".").toIntOrNull() ?: 0 }
    }

    private fun findLocalIpv4(context: Context): Inet4Address? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        return cm.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
    }

    private fun isPortOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    // ---------------- ONVIF WS-Discovery ----------------

    private fun wsDiscovery(context: Context): List<DiscoveredCamera> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Без MulticastLock Wi-Fi-драйвер может отбрасывать multicast-пакеты
        val lock = wifi?.createMulticastLock("DenIPCamDiscovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val found = mutableMapOf<String, DiscoveredCamera>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 500
                val target = InetAddress.getByName(WS_DISCOVERY_ADDRESS)
                // Часть камер отвечает только на запрос с типом устройства, часть — только без него
                for (withType in listOf(true, false)) {
                    val probe = probeMessage(withType).toByteArray(Charsets.UTF_8)
                    socket.send(DatagramPacket(probe, probe.size, target, WS_DISCOVERY_PORT))
                }

                val buffer = ByteArray(16 * 1024)
                val deadline = System.currentTimeMillis() + WS_DISCOVERY_WAIT_MS
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    parseProbeMatch(response, packet.address.hostAddress ?: continue)?.let { device ->
                        found.putIfAbsent(device.host, device)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WS-Discovery failed: ${e.message}")
        } finally {
            lock?.release()
        }
        return found.values.toList()
    }

    private fun probeMessage(withType: Boolean): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" """ +
            """xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" """ +
            """xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" """ +
            """xmlns:dn="http://www.onvif.org/ver10/network/wsdl">""" +
            "<e:Header>" +
            "<w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID>" +
            """<w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To>""" +
            """<w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action>""" +
            "</e:Header>" +
            "<e:Body><d:Probe>" +
            (if (withType) "<d:Types>dn:NetworkVideoTransmitter</d:Types>" else "") +
            "</d:Probe></e:Body></e:Envelope>"

    private fun parseProbeMatch(response: String, senderHost: String): DiscoveredCamera? {
        if (!response.contains("ProbeMatch")) return null

        // XAddrs: адреса сервиса устройства, берём IPv4-адрес из нашей сети
        val xAddrs = Regex("""<(?:\w+:)?XAddrs>([^<]+)<""").find(response)?.groupValues?.get(1)
            ?.split(Regex("""\s+"""))?.filter { it.isNotBlank() }.orEmpty()
        val xAddr = xAddrs.map { android.net.Uri.parse(it) }
            .firstOrNull { it.host == senderHost }
            ?: xAddrs.map { android.net.Uri.parse(it) }.firstOrNull { it.host?.contains('.') == true }
        val onvifPort = xAddr?.let { if (it.port > 0) it.port else 80 }

        // Scopes: onvif://www.onvif.org/name/... и onvif://www.onvif.org/hardware/...
        val scopes = Regex("""<(?:\w+:)?Scopes[^>]*>([^<]+)<""").find(response)?.groupValues?.get(1).orEmpty()
        fun scope(key: String) = Regex("""onvif://www\.onvif\.org/$key/(\S+)""").find(scopes)?.groupValues?.get(1)
            ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            ?.replace('_', ' ')

        return DiscoveredCamera(
            host = senderHost,
            rtspPort = null,
            onvifPort = onvifPort,
            name = scope("name"),
            hardware = scope("hardware")
        )
    }
}
