package pl.u1companion.printer.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Drukarka znaleziona przez mDNS.
 *
 * Snapmaker U1 (firmware u1-moonraker, komponent zeroconf.py) ogłasza usługę
 * `_snapmaker._tcp` na porcie 1884 (LAN MQTT) z rekordami TXT:
 * version, machine_type, device_name, sn, link_mode (lan/wan), userid, ip, region.
 * Hostname mDNS: "U1-<sn>.local". API HTTP Moonraker jest na tym samym IP, port 7125
 * (lava/moonraker.conf). Zweryfikowane w kodzie źródłowym 2026-10-17, NIE na fizycznym urządzeniu.
 *
 * Fallback: standardowe instalacje Moonrakera mogą ogłaszać `_moonraker._tcp`.
 */
data class DiscoveredPrinter(
    val id: String,
    val name: String,
    val host: String,
    val moonrakerPort: Int,
    val serviceType: String,
    val txt: Map<String, String>,
) {
    val isSnapmakerU1: Boolean get() = serviceType.startsWith("_snapmaker") || txt["machine_type"]?.contains("U1", true) == true
}

@Singleton
class PrinterDiscovery @Inject constructor(@ApplicationContext private val context: Context) {

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /** Strumień znalezionych drukarek; kończy wykrywanie po anulowaniu kolektora. */
    fun discover(): Flow<DiscoveredPrinter> = callbackFlow {
        // Część telefonów filtruje multicast bez MulticastLock.
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("u1-mdns").apply { setReferenceCounted(false); acquire() }

        // Starsze Androidy nie pozwalają na równoległe resolveService -> kolejka.
        val resolveQueue = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val resolver = launch {
            for (info in resolveQueue) {
                resolve(info)?.let { trySend(it) }
            }
        }

        val listeners = SERVICE_TYPES.map { type ->
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) { resolveQueue.trySend(serviceInfo) }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }.also { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) }
        }

        awaitClose {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            resolver.cancel()
            resolveQueue.close()
            lock.release()
        }
    }

    @Suppress("DEPRECATION") // resolveService: na API 34+ docelowo registerServiceInfoCallback
    private suspend fun resolve(info: NsdServiceInfo): DiscoveredPrinter? = suspendCancellableCoroutine { cont ->
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onServiceResolved(si: NsdServiceInfo) {
                val txt = si.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
                val host = txt["ip"]?.takeIf { it.isNotBlank() } ?: si.host?.hostAddress
                val type = si.serviceType.trim('.')
                val result = host?.let {
                    DiscoveredPrinter(
                        id = txt["sn"]?.takeIf { s -> s.isNotBlank() } ?: "$it:$MOONRAKER_DEFAULT_PORT",
                        name = txt["device_name"]?.takeIf { s -> s.isNotBlank() } ?: si.serviceName,
                        host = it,
                        // Dla _moonraker._tcp port usługi = port HTTP; dla _snapmaker._tcp port to MQTT (1884).
                        moonrakerPort = if (type.startsWith("_moonraker")) si.port else MOONRAKER_DEFAULT_PORT,
                        serviceType = type,
                        txt = txt,
                    )
                }
                if (cont.isActive) cont.resume(result)
            }
        })
    }

    companion object {
        const val MOONRAKER_DEFAULT_PORT = 7125
        val SERVICE_TYPES = listOf("_snapmaker._tcp.", "_moonraker._tcp.")
    }
}
