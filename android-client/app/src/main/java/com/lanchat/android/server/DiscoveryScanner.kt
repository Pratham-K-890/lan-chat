package com.lanchat.android.server

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket

/**
 * UDP discovery scanner — runs on the JOINING phone.
 *
 * Listens on [DiscoveryBeacon.DISCOVERY_PORT] for the host's broadcast packet.
 * When found, emits the host's IP+port via [discovered] StateFlow so the UI
 * can auto-fill the server IP field.
 *
 * Usage:
 *   DiscoveryScanner.start()
 *   // Observe: DiscoveryScanner.discovered.collect { (ip, port) -> fill field }
 *   DiscoveryScanner.stop()
 */
object DiscoveryScanner {

    private const val TAG     = "DiscoveryScanner"
    private const val BUF_SIZE = 256
    private const val TIMEOUT_MS = 500  // check isActive every 500ms

    data class HostInfo(val ip: String, val port: Int)

    private val _discovered = MutableStateFlow<HostInfo?>(null)
    val discovered: StateFlow<HostInfo?> = _discovered.asStateFlow()

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /** Start scanning for a host beacon. Updates [discovered] when found. */
    fun start() {
        if (job?.isActive == true) return
        _discovered.value = null
        Log.i(TAG, "Starting discovery scanner on port ${DiscoveryBeacon.DISCOVERY_PORT}")

        job = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(DiscoveryBeacon.DISCOVERY_PORT).apply {
                    reuseAddress  = true
                    soTimeout     = TIMEOUT_MS
                    broadcast     = true
                }

                val buf = ByteArray(BUF_SIZE)

                while (isActive) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                        val msg = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        Log.d(TAG, "Received: $msg")

                        if (msg.startsWith(DiscoveryBeacon.PACKET_PREFIX)) {
                            val payload = msg.removePrefix(DiscoveryBeacon.PACKET_PREFIX)
                            val parts   = payload.split(":")
                            if (parts.size == 2) {
                                val ip   = parts[0]
                                val port = parts[1].toIntOrNull() ?: 9090
                                Log.i(TAG, "Host discovered: $ip:$port")
                                _discovered.value = HostInfo(ip, port)
                                // Keep scanning — host might change
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Timeout every 500ms — just loop to check isActive
                    } catch (e: Exception) {
                        if (isActive) Log.w(TAG, "Receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Scanner socket error: ${e.message}", e)
            } finally {
                socket?.close()
                Log.i(TAG, "Discovery scanner stopped.")
            }
        }
    }

    /** Stop scanning. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Reset the last discovered host (e.g. when switching to HOST mode). */
    fun reset() {
        stop()
        _discovered.value = null
    }
}
