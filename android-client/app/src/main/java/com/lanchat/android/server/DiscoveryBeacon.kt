package com.lanchat.android.server

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * UDP discovery beacon — runs on the HOST phone.
 *
 * Broadcasts a small packet every [INTERVAL_MS] to the LAN broadcast address
 * so JOIN-mode phones can automatically find the host's IP without typing it.
 *
 * Packet format (plain text):
 *   "LANCHAT_HOST:<ip>:<port>"
 *   e.g. "LANCHAT_HOST:192.168.1.105:9090"
 *
 * The [DiscoveryScanner] on the joining phone listens for this packet.
 *
 * Uses port 9092 (separate from chat TCP 9090 and voice UDP 9091).
 */
object DiscoveryBeacon {

    private const val TAG          = "DiscoveryBeacon"
    const val DISCOVERY_PORT       = 9092
    private const val INTERVAL_MS  = 2_000L
    private const val BROADCAST_IP = "255.255.255.255"
    const val PACKET_PREFIX        = "LANCHAT_HOST:"

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * Start broadcasting the host's IP.
     * @param hostIp  this phone's LAN IP (from EmbeddedServer.localIp)
     * @param port    chat server port (default 9090)
     */
    fun start(hostIp: String, port: Int = 9090) {
        if (job?.isActive == true) return
        Log.i(TAG, "Starting discovery beacon: $hostIp:$port")

        job = scope.launch {
            val message = "$PACKET_PREFIX$hostIp:$port".toByteArray(Charsets.UTF_8)
            val target  = InetAddress.getByName(BROADCAST_IP)

            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    // Reuse address so restart works cleanly
                    reuseAddress = true
                }

                while (isActive) {
                    try {
                        val packet = DatagramPacket(message, message.size, target, DISCOVERY_PORT)
                        socket.send(packet)
                        Log.v(TAG, "Beacon sent: ${String(message)}")
                    } catch (e: Exception) {
                        // ENETUNREACH = no hotspot/wifi yet — silently wait, don't spam logs
                        val msg = e.message ?: ""
                        if (!msg.contains("ENETUNREACH") && !msg.contains("ENONET")) {
                            Log.w(TAG, "Beacon send failed: $msg")
                        }
                    }
                    delay(INTERVAL_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Beacon socket error: ${e.message}", e)
            } finally {
                socket?.close()
                Log.i(TAG, "Discovery beacon stopped.")
            }
        }
    }

    /** Stop broadcasting. */
    fun stop() {
        job?.cancel()
        job = null
        Log.i(TAG, "Discovery beacon stop requested.")
    }
}
