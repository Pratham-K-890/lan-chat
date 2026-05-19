package com.lanchat.android.server

import android.util.Log
import com.lanchat.server.ChatServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

object EmbeddedServer {

    private const val TAG = "EmbeddedServer"

    enum class State { IDLE, STARTING, RUNNING, ERROR, STOPPED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private var serverThread: Thread? = null
    private var lastError: String = ""

    /**
     * Gets the best available LAN/hotspot IP.
     * Priority order:
     *   1. Hotspot IP (192.168.43.x, 192.168.x.x, 10.x.x.x)
     *   2. Any non-loopback IPv4
     * Falls back to "192.168.43.1" — the standard Android hotspot IP.
     */
    val localIp: String
        get() {
            return try {
                val addresses = NetworkInterface.getNetworkInterfaces()
                    ?.toList()
                    ?.flatMap { it.inetAddresses.toList() }
                    ?.filterIsInstance<Inet4Address>()
                    ?.filter { addr ->
                        !addr.isLoopbackAddress &&
                        addr.hostAddress?.startsWith("169.254") != true // exclude APIPA
                    }
                    ?: emptyList()

                // Prefer hotspot IP ranges (wlan0 AP mode, usb tethering, etc.)
                val hotspotIp = addresses.firstOrNull { addr ->
                    val h = addr.hostAddress ?: return@firstOrNull false
                    h.startsWith("192.168.43.") || // Android standard hotspot
                    h.startsWith("192.168.1.")  ||
                    h.startsWith("192.168.0.")  ||
                    h.startsWith("10.0.")
                }

                hotspotIp?.hostAddress
                    ?: addresses.firstOrNull()?.hostAddress
                    ?: "192.168.43.1" // standard Android hotspot IP fallback

            } catch (e: Exception) {
                Log.e(TAG, "Could not determine local IP", e)
                "192.168.43.1"
            }
        }

    fun start() {
        if (_state.value == State.RUNNING || _state.value == State.STARTING) {
            Log.w(TAG, "Server already running — ignoring start()")
            return
        }

        lastError = ""
        _state.value = State.STARTING

        serverThread = Thread({
            try {
                Log.i(TAG, "Starting embedded ChatServer on port ${ChatServer.PORT}")
                ChatServer.startEmbedded()
                _state.value = State.STOPPED
                Log.i(TAG, "ChatServer stopped cleanly.")
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                _state.value = State.ERROR
                Log.e(TAG, "ChatServer error: $lastError", e)
            }
        }, "embedded-server-thread").apply {
            isDaemon = true
            start()
        }

        // Poll until server is ready (max 5 seconds)
        Thread {
            var waited = 0
            while (!ChatServer.isRunning() && waited < 50) {
                Thread.sleep(100)
                waited++
            }
            if (_state.value == State.STARTING) {
                _state.value = if (ChatServer.isRunning()) State.RUNNING else State.ERROR
                if (_state.value == State.ERROR && lastError.isEmpty()) {
                    lastError = "Server failed to start. Port 9090 may be in use."
                }
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        if (_state.value == State.IDLE || _state.value == State.STOPPED) return
        Log.i(TAG, "Stopping embedded ChatServer...")
        ChatServer.stop()
        serverThread?.interrupt()
        serverThread = null
        _state.value = State.STOPPED
    }

    fun isRunning(): Boolean = _state.value == State.RUNNING
    fun getLastError(): String = lastError
    val port: Int get() = ChatServer.PORT
}
