package com.flow.claudepush

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

class PushService : Service() {
    private var server: PushServer? = null
    private var nsd: NsdHelper? = null
    private var repo: FileRepository? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        repo = FileRepository(this)
        server = PushServer(repo!!, onFileReceived = {
            notifyFileChange()
        }, onMacDetected = { host, port ->
            // announce=false: Mac told us about itself, don't echo back
            nsd?.saveMacHost(host, port, announce = false)
        }, onClipboardReceived = { text ->
            setClipboard(text)
        })
        nsd = NsdHelper(this)
        _nsdRef = nsd
        registerWifiCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ip = getWifiIp()
        val notification = buildNotification(ip)
        startForeground(NOTIFICATION_ID, notification)

        acquireLocks()

        try {
            val srv = server
            if (srv != null && !srv.isAlive) {
                srv.start()
                Log.i(TAG, "Push server started on $ip:$SERVER_PORT")
            }
            nsd?.register(SERVER_PORT)
            // Mac discovers phone and announces itself — no phone-side scanning needed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server", e)
            stopSelf()
        }

        return START_STICKY
    }

    // No periodic recheck needed — Mac announces itself every 30s via _phone_checker.
    // Phone trusts announce and only clears macHost on WiFi network change.

    override fun onDestroy() {
        unregisterWifiCallback()
        handler.removeCallbacksAndMessages(null)
        nsd?.unregister()
        server?.stop()
        releaseLocks()
        _nsdRef = null
        _wifiNetwork = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── WiFi Network Binding ─────────────────────────────────

    private fun registerWifiCallback() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val isRealChange = _wifiNetwork != null && _wifiNetwork != network
                Log.i(TAG, "WiFi network available: $network (realChange=$isRealChange)")
                _wifiNetwork = network
                updateNotification()
                broadcastNetworkState()
                if (isRealChange) {
                    // WiFi actually changed — clear old Mac, wait for Mac to announce
                    nsd?.onNetworkChanged()
                    nsd?.register(SERVER_PORT)
                    Log.i(TAG, "WiFi changed → cleared Mac, waiting for announce")
                } else {
                    nsd?.register(SERVER_PORT)
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "WiFi network lost: $network")
                if (_wifiNetwork == network) {
                    _wifiNetwork = null
                }
                // Clear stale macHost — old IP is unreachable now (BUG #2 fix)
                nsd?.onNetworkChanged()
                updateNotification()
                broadcastNetworkState()
                // WiFi lost — Mac will find us and announce when ready
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.i(TAG, "WiFi link properties changed")
                if (_wifiNetwork == network) {
                    updateNotification()
                    broadcastNetworkState()
                }
            }
        }

        cm.registerNetworkCallback(request, callback)
        networkCallback = callback

        // Also try to get the current WiFi network immediately
        val activeNetwork = cm.activeNetwork
        if (activeNetwork != null) {
            val caps = cm.getNetworkCapabilities(activeNetwork)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                _wifiNetwork = activeNetwork
            }
        }
    }

    private fun unregisterWifiCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        networkCallback = null
    }

    private fun updateNotification() {
        val ip = getWifiIp()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(ip))
    }

    private fun broadcastNetworkState() {
        sendBroadcast(Intent(ACTION_NETWORK_CHANGED).setPackage(packageName))
    }

    // ── Locks ────────────────────────────────────────────────

    private fun acquireLocks() {
        // WiFi lock: keep WiFi active when screen is off
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ClaudePush:wifi"
            )
        }
        wifiLock?.takeIf { !it.isHeld }?.acquire()

        // Partial wake lock: keep CPU running
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ClaudePush:wake"
            )
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire(24 * 60 * 60 * 1000L) // 24h safety timeout
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    // ── Notification ─────────────────────────────────────────

    private fun buildNotification(ip: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val wifiName = getWifiName()
        val hotspotIp = getHotspotIp()
        val subtitle = if (ip == "0.0.0.0" && hotspotIp == null) {
            "No WiFi — waiting for connection"
        } else if (ip == "0.0.0.0" && hotspotIp != null) {
            "Hotspot mode | $hotspotIp:$SERVER_PORT"
        } else if (wifiName != null) {
            "$wifiName | $ip:$SERVER_PORT"
        } else {
            "Receiving on $ip:$SERVER_PORT"
        }

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claude Push")
            .setContentText(subtitle)
            .setSmallIcon(R.drawable.ic_notif_ouroboros)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun getWifiName(): String? = getWifiName(applicationContext)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Push Service", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the file receiver running"
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notifyFileChange() {
        sendBroadcast(Intent(ACTION_FILE_CHANGED).setPackage(packageName))
    }

    private fun setClipboard(text: String) {
        // Must run on main thread for clipboard access
        android.os.Handler(mainLooper).post {
            try {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("claude_push", text))
                // Show notification with the text
                val preview = if (text.length > 50) text.take(50) + "..." else text
                val nm = getSystemService(NotificationManager::class.java)
                val notif = Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("Clipboard from Mac")
                    .setContentText(preview)
                    .setSmallIcon(R.drawable.ic_notif_ouroboros)
                    .setStyle(Notification.BigTextStyle().bigText(text.take(500)))
                    .setAutoCancel(true)
                    .build()
                nm.notify(CLIPBOARD_NOTIFICATION_ID, notif)
                Log.i(TAG, "Clipboard set from Mac: ${text.length} chars")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set clipboard", e)
            }
        }
    }

    companion object {
        const val TAG = "PushService"
        const val CHANNEL_ID = "push_service"
        const val NOTIFICATION_ID = 1
        const val CLIPBOARD_NOTIFICATION_ID = 2
        const val ACTION_FILE_CHANGED = "com.flow.claudepush.FILE_CHANGED"
        const val ACTION_NETWORK_CHANGED = "com.flow.claudepush.NETWORK_CHANGED"
        const val SERVER_PORT = 18080

        private var _nsdRef: NsdHelper? = null

        /** WiFi Network object for binding connections. */
        @Volatile
        var _wifiNetwork: Network? = null
            private set

        /** Mac host discovered via HTTP scan, read from MainActivity. */
        val macHost: String? get() = _nsdRef?.macHost
        val macPort: Int get() = _nsdRef?.macPort ?: NsdHelper.MAC_PORT

        fun restoreMac() {
            _nsdRef?.restoreFromPrefs()
        }

        /** Get the WiFi Network object for binding HTTP connections. */
        fun getWifiNetwork(): Network? = _wifiNetwork

        /**
         * Get WiFi IP from the bound WiFi network's LinkProperties.
         * Falls back to iterating NetworkInterface if no WiFi network is bound.
         */
        /** Categorized IPv4 addresses from network interfaces (single enumeration). */
        private data class NetAddresses(
            val wlanIp: String? = null,
            val hotspotIp: String? = null,
            val fallbackIp: String? = null
        )

        private fun enumerateIpv4(): NetAddresses {
            var wlan: String? = null
            var hotspot: String? = null
            var fallback: String? = null
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val name = iface.name
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            when {
                                name.startsWith("wlan") && !isHotspotInterface(name) -> wlan = wlan ?: ip
                                isHotspotInterface(name) -> hotspot = hotspot ?: ip
                                else -> fallback = fallback ?: ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return NetAddresses(wlan, hotspot, fallback)
        }

        /**
         * Get all reachable IPv4 addresses for this device, categorized by type.
         * Used by /status to let Mac pick the fastest route.
         */
        fun getAllIps(): Map<String, String> {
            val ips = mutableMapOf<String, String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    val name = iface.name
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            when {
                                name.startsWith("wlan") && !isHotspotInterface(name) -> ips["lan"] = ip
                                isHotspotInterface(name) -> ips["hotspot"] = ip
                                // Tailscale uses 100.64.0.0/10 (second octet 64-127)
                                isTailscaleIp(ip) -> ips["tailscale"] = ip
                                else -> ips.putIfAbsent("other", ip)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return ips
        }

        fun getWifiIp(): String {
            val addrs = enumerateIpv4()
            return addrs.wlanIp ?: addrs.hotspotIp ?: addrs.fallbackIp ?: "0.0.0.0"
        }

        /** Check if this is likely a hotspot/tethering interface name. */
        private fun isHotspotInterface(name: String): Boolean {
            return name.startsWith("ap") ||        // common hotspot interface
                    name == "swlan0" ||             // Samsung/vivo hotspot
                    name == "wlan1" ||              // some devices use wlan1 for AP
                    name.startsWith("rndis") ||     // USB tethering
                    name.startsWith("bt-pan")       // Bluetooth tethering
        }

        /** Check if IP is in Tailscale's CGNAT range 100.64.0.0/10 */
        private fun isTailscaleIp(ip: String): Boolean {
            if (!ip.startsWith("100.")) return false
            val second = ip.split(".").getOrNull(1)?.toIntOrNull() ?: return false
            return second in 64..127
        }

        /** Get the hotspot/tethering IP if active, or null. */
        fun getHotspotIp(): String? = enumerateIpv4().hotspotIp

        /** Get WiFi SSID name, or null. */
        fun getWifiName(context: android.content.Context): String? {
            return try {
                val wifiManager = context.applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val info = wifiManager.connectionInfo
                val ssid = info?.ssid
                if (ssid != null && ssid != "<unknown ssid>" && ssid != "0x") {
                    ssid.removeSurrounding("\"")
                } else null
            } catch (_: Exception) { null }
        }

        /**
         * Get WiFi IP using ConnectivityManager LinkProperties (requires context).
         * This is the most reliable method — always returns WiFi-specific IP.
         */
        /** Extract first IPv4 address from LinkProperties, or null. */
        private fun ipv4FromLinkProperties(lp: LinkProperties?): String? {
            lp ?: return null
            for (addr in lp.linkAddresses) {
                val inetAddr = addr.address
                if (inetAddr is Inet4Address && !inetAddr.isLoopbackAddress) {
                    return inetAddr.hostAddress
                }
            }
            return null
        }

        fun getWifiIp(context: android.content.Context): String {
            try {
                val cm = context.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
                val wifiNet = _wifiNetwork
                if (wifiNet != null) {
                    ipv4FromLinkProperties(cm.getLinkProperties(wifiNet))?.let { return it }
                }
                // Fallback: check active network if it's WiFi
                val activeNet = cm.activeNetwork
                if (activeNet != null) {
                    val caps = cm.getNetworkCapabilities(activeNet)
                    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                        ipv4FromLinkProperties(cm.getLinkProperties(activeNet))?.let { return it }
                    }
                }
                // When Tailscale VPN is active, activeNetwork may be VPN not WiFi.
                // Enumerate all networks to find the WiFi one underneath.
                // Note: do NOT update _wifiNetwork here — only NetworkCallback should manage that state.
                for (net in cm.allNetworks) {
                    val c = cm.getNetworkCapabilities(net) ?: continue
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                        ipv4FromLinkProperties(cm.getLinkProperties(net))?.let {
                            return it
                        }
                    }
                }
            } catch (_: Exception) {}
            return getWifiIp()
        }
    }
}
