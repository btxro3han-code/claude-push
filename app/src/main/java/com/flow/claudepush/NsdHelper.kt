package com.flow.claudepush

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class NsdHelper(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val prefs = context.getSharedPreferences("claude_push", Context.MODE_PRIVATE)
    private var registered = false

    /** Discovered Mac address. */
    @Volatile
    var macHost: String? = prefs.getString("mac_host", null)
        private set
    @Volatile
    var macPort: Int = prefs.getInt("mac_port", MAC_PORT)
        private set

    // ── Service Registration (phone advertises itself) ──────

    private var currentRegListener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        if (registered) return
        val info = NsdServiceInfo().apply {
            serviceName = "ClaudePush-${Build.MODEL.replace(" ", "")}"
            serviceType = "_claudepush._tcp"
            setPort(port)
        }
        // Each registration needs a fresh listener (Android NSD won't reuse a listener)
        val listener = object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(si: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "NSD registration failed: $errorCode")
            }
            override fun onUnregistrationFailed(si: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceRegistered(si: NsdServiceInfo) {
                Log.i(TAG, "NSD registered: ${si.serviceName}")
            }
            override fun onServiceUnregistered(si: NsdServiceInfo) {}
        }
        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            currentRegListener = listener
            registered = true
        } catch (e: Exception) {
            Log.e(TAG, "NSD register error", e)
        }
    }

    fun unregister() {
        if (!registered) return
        try { currentRegListener?.let { nsdManager.unregisterService(it) } } catch (_: Exception) {}
        currentRegListener = null
        registered = false
    }

    // ── Network Change Handler ───────────────────────────────

    /**
     * Called when WiFi network changes. Clears cached Mac IP and
     * unregisters NSD so it can be re-registered with new IP.
     */
    fun onNetworkChanged() {
        Log.i(TAG, "Network changed — clearing cached Mac host and re-registering NSD")
        // Clear cached Mac host
        macHost = null
        macPort = MAC_PORT
        prefs.edit()
            .remove("mac_host")
            .remove("mac_port")
            .apply()
        // Unregister NSD (new listener will be created on next register())
        unregister()
    }

    // ── Mac Discovery via HTTP scan ─────────────────────────

    private val scanning = AtomicBoolean(false)

    /**
     * Find Mac by HTTP: first check saved IP, then scan subnet.
     * Much more reliable than NSD on Chinese Android phones.
     */
    fun discoverMac() {
        if (scanning.getAndSet(true)) return
        Thread {
            try {
                // 1. Try saved IP first (longer timeout, most reliable)
                val savedHost = prefs.getString("mac_host", null)
                if (savedHost != null && checkMac(savedHost, macPort, 3000)) {
                    macHost = savedHost
                    Log.i(TAG, "Mac verified at saved IP: $savedHost:$macPort")
                    return@Thread
                }

                // 2. Collect all available IPs to scan from (WiFi client + hotspot)
                val scanIps = mutableListOf<String>()

                // WiFi client IP
                val wifiIp = PushService.getWifiIp(context)
                if (wifiIp != "0.0.0.0") {
                    scanIps.add(wifiIp)
                }

                // Hotspot IP (phone is AP — Mac might be connected as client)
                val hotspotIp = PushService.getHotspotIp()
                if (hotspotIp != null && hotspotIp !in scanIps) {
                    scanIps.add(hotspotIp)
                    Log.i(TAG, "Hotspot active at $hotspotIp, will scan for Mac clients")
                }

                if (scanIps.isEmpty()) {
                    Log.i(TAG, "No network IP available (no WiFi, no hotspot), skipping scan")
                    return@Thread
                }

                // Build unique list of subnets to scan from all IPs
                val subnets = mutableListOf<String>()
                for (ip in scanIps) {
                    val parts = ip.split(".")
                    if (parts.size != 4) continue
                    val prefix = "${parts[0]}.${parts[1]}"
                    val thirdOctet = parts[2].toIntOrNull() ?: continue
                    val base = "$prefix.$thirdOctet"
                    if (base !in subnets) subnets.add(base)
                    // Add neighbor subnets only for WiFi client (hotspot subnet is usually small)
                    if (ip == wifiIp) {
                        for (offset in 1..NEIGHBOR_SUBNET_RANGE) {
                            val sub1 = "$prefix.${thirdOctet - offset}"
                            val sub2 = "$prefix.${thirdOctet + offset}"
                            if (thirdOctet - offset >= 0 && sub1 !in subnets) subnets.add(sub1)
                            if (thirdOctet + offset <= 255 && sub2 !in subnets) subnets.add(sub2)
                        }
                    }
                }

                val myIps = scanIps.toSet()
                Log.i(TAG, "Scanning ${subnets.size} subnet(s) for Mac (my IPs: ${scanIps.joinToString()}): ${subnets.joinToString()}")

                val found = AtomicBoolean(false)
                for (subnet in subnets) {
                    if (found.get()) break
                    Log.i(TAG, "Scanning subnet $subnet.*")
                    val executor = Executors.newFixedThreadPool(30)
                    try {
                        for (i in 1..254) {
                            if (found.get()) break
                            val ip = "$subnet.$i"
                            if (ip in myIps) continue
                            executor.submit {
                                if (!found.get() && checkMac(ip, MAC_PORT, 1500)) {
                                    found.set(true)
                                    macHost = ip
                                    macPort = MAC_PORT
                                    prefs.edit()
                                        .putString("mac_host", ip)
                                        .putInt("mac_port", MAC_PORT)
                                        .apply()
                                    Log.i(TAG, "Mac found via scan: $ip:18081")
                                }
                            }
                        }
                    } finally {
                        executor.shutdown()
                    }
                    if (!executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS)) {
                        executor.shutdownNow()
                    }
                }

                if (!found.get()) {
                    Log.i(TAG, "Mac not found on ${subnets.size} subnet(s)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            } finally {
                scanning.set(false)
            }
        }.start()
    }

    /** Periodic re-check: verify Mac is still reachable, or re-scan. */
    fun recheckMac() {
        val host = macHost
        if (host != null && checkMac(host, macPort, 3000)) return
        // Don't clear macHost — keep saved value as fallback for uploads
        // Just try to discover again in background
        discoverMac()
    }

    /** Check if current Mac is reachable (used by PushService for retry logic). */
    fun isMacReachable(): Boolean {
        val host = macHost ?: return false
        return checkMac(host, macPort, 3000)
    }

    /** Called when Mac is auto-detected from an incoming push connection. */
    fun saveMacHost(host: String, port: Int) {
        macHost = host
        macPort = port
        prefs.edit()
            .putString("mac_host", host)
            .putInt("mac_port", port)
            .apply()
        Log.i(TAG, "Mac saved: $host:$port")
    }

    private fun checkMac(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val url = URL("http://$host:$port/status")
            // Bind to WiFi network if available
            val wifiNetwork = PushService.getWifiNetwork()
            val conn = if (wifiNetwork != null) {
                wifiNetwork.openConnection(url) as HttpURLConnection
            } else {
                url.openConnection() as HttpURLConnection
            }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.useCaches = false
            val code = conn.responseCode
            if (code != 200) { conn.disconnect(); return false }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val ok = body.contains("macOS")
            if (!ok) Log.d(TAG, "checkMac $host:$port → code=$code")
            ok
        } catch (e: Exception) {
            Log.d(TAG, "checkMac $host:$port → ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "NsdHelper"
        /** Mac receiver port — must match PORT in claude_push_mac.py */
        const val MAC_PORT = 18081
        /** How many neighboring subnets to scan (e.g. 2 = scan ±2 from own third octet) */
        private const val NEIGHBOR_SUBNET_RANGE = 2
    }
}
