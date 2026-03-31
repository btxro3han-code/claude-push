package com.flow.claudepush

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

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

    // ── Mac Verification (no scanning — Mac announces itself) ──

    /** Restore macHost from prefs if not set (e.g. after process restart). */
    fun restoreFromPrefs() {
        if (macHost == null) {
            macHost = prefs.getString("mac_host", null)
            macPort = prefs.getInt("mac_port", MAC_PORT)
        }
    }

    /** Called when Mac is auto-detected from an incoming push connection or /announce. */
    fun saveMacHost(host: String, port: Int, announce: Boolean = true) {
        val isNew = host != macHost
        macHost = host
        macPort = port
        prefs.edit()
            .putString("mac_host", host)
            .putInt("mac_port", port)
            .apply()
        Log.i(TAG, "Mac saved: $host:$port")
        // Announce ourselves to Mac so it knows about us too
        if (isNew && announce) {
            Thread { announceToMac(host, port) }.start()
        }
    }

    /** Tell the Mac our IP so it knows about us immediately. */
    private fun announceToMac(macHost: String, macPort: Int) {
        try {
            val myIp = PushService.getWifiIp(context)
            if (myIp == "0.0.0.0") return
            val json = org.json.JSONObject()
                .put("host", myIp)
                .put("port", PushService.SERVER_PORT)
            val url = URL("http://$macHost:$macPort/announce")
            val conn = url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.outputStream.use { it.write(json.toString().toByteArray()) }
            val code = conn.responseCode
            conn.disconnect()
            Log.i(TAG, "Announced to Mac $macHost:$macPort → HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to announce to Mac: ${e.message}")
        }
    }

    private fun checkMac(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            val url = URL("http://$host:$port/status")
            val conn = url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
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
    }
}
