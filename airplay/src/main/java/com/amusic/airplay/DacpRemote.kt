package com.amusic.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Controls the iPhone from the TV remote, like an Apple TV or a HomePod does (DACP).
 *
 * While streaming, the iPhone gives us its DACP id and a token ("Active-Remote") and publishes a
 * "_dacp._tcp" service named "iTunes_Ctrl_<id>". We find it with NsdManager and send it plain
 * HTTP requests such as GET /ctrl-int/1/playpause.
 */
class DacpRemote(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var dacpId: String? = null
    private var token: String? = null
    private var target: InetSocketAddress? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var resolving = false

    /** True once the iPhone's control service has been found. */
    val available: Boolean get() = synchronized(lock) { target != null && token != null }

    /** Called (on a core thread) whenever the sender tells us its DACP id and token. */
    fun setClient(id: String, activeRemote: String) {
        synchronized(lock) {
            if (id.equals(dacpId, ignoreCase = true) && activeRemote == token) return
            val sameDevice = id.equals(dacpId, ignoreCase = true)
            dacpId = id
            token = activeRemote
            if (sameDevice && target != null) return
            target = null
        }
        startDiscovery()
    }

    fun clear() {
        synchronized(lock) { dacpId = null; token = null; target = null }
        stopDiscovery()
    }

    fun playPause() = send("playpause")
    fun play() = send("play")
    fun pause() = send("pause")
    fun next() = send("nextitem")
    fun previous() = send("previtem")
    fun volumeUp() = send("volumeup")
    fun volumeDown() = send("volumedown")

    private fun send(command: String) {
        val (addr, tok) = synchronized(lock) { target to token }
        if (addr == null || tok == null) return
        io.execute {
            runCatching {
                Socket().use { s ->
                    s.connect(addr, 2000)
                    s.soTimeout = 3000
                    val host = addr.address.hostAddress?.substringBefore('%').orEmpty()
                    val req = "GET /ctrl-int/1/$command HTTP/1.1\r\n" +
                        "Host: ${if (host.contains(':')) "[$host]" else host}:${addr.port}\r\n" +
                        "Active-Remote: $tok\r\n" +
                        "Connection: close\r\n\r\n"
                    s.getOutputStream().apply { write(req.toByteArray()); flush() }
                    val status = s.getInputStream().bufferedReader().readLine()
                    Log.i(TAG, "$command → $status")
                }
            }.onFailure { Log.w(TAG, "$command failed: $it") }
        }
    }

    // ---------- finding the iPhone's _dacp._tcp service ----------

    private fun startDiscovery() {
        stopDiscovery()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Log.w(TAG, "discovery failed: $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onServiceFound(service: NsdServiceInfo) {
                val id = synchronized(lock) { dacpId } ?: return
                if (service.serviceName.equals("iTunes_Ctrl_$id", ignoreCase = true)) resolve(service)
            }
        }
        synchronized(lock) { discovery = listener }
        runCatching { nsd.discoverServices("_dacp._tcp", NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discoverServices: $it") }
    }

    private fun stopDiscovery() {
        val d = synchronized(lock) { discovery.also { discovery = null } } ?: return
        runCatching { nsd.stopServiceDiscovery(d) }
    }

    private fun resolve(service: NsdServiceInfo) {
        synchronized(lock) { if (resolving) return; resolving = true }
        @Suppress("DEPRECATION")
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolving = false }
                Log.w(TAG, "resolve failed: $errorCode")
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                val host: InetAddress? = info.host
                synchronized(lock) {
                    resolving = false
                    if (host != null) target = InetSocketAddress(host, info.port)
                }
                Log.i(TAG, "iPhone remote control at $host:${info.port}")
                stopDiscovery()
            }
        })
    }

    private companion object {
        const val TAG = "AirPlayRemote"
    }
}
