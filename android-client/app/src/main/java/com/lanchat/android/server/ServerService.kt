package com.lanchat.android.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.lanchat.android.LanChatApplication
import com.lanchat.client.MessageSender
import com.lanchat.android.R
import com.lanchat.android.network.ChatRepository
import com.lanchat.android.ui.login.LoginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Paths

/**
 * Foreground Service that owns the entire HOST lifecycle:
 *
 *   1. Starts EmbeddedServer on a background thread
 *   2. Waits for it to be ready
 *   3. Connects the local client to 127.0.0.1 (loopback)
 *   4. Logs in with saved credentials
 *   5. Stores the connected ChatRepository in LanChatApplication
 *   6. Keeps everything alive regardless of Activity lifecycle
 *
 * This service uses its OWN CoroutineScope (SupervisorJob) so coroutines
 * are NEVER cancelled by any Activity or ViewModel lifecycle.
 */
class ServerService : Service() {

    companion object {
        private const val TAG            = "ServerService"
        private const val CHANNEL_ID     = "lanchat_server_channel"
        private const val NOTIFICATION_ID = 2001
        const val EXTRA_USERNAME         = "username"
        const val EXTRA_PASSWORD         = "password"

        // Status broadcast — Activities observe this to update UI
        const val ACTION_READY           = "com.lanchat.SERVER_READY"
        const val ACTION_ERROR           = "com.lanchat.SERVER_ERROR"
        const val EXTRA_HOST_IP          = "host_ip"
        const val EXTRA_ERROR_MSG        = "error_msg"

        fun start(context: Context, username: String, password: String) {
            val i = Intent(context, ServerService::class.java).apply {
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }

    // Service-owned scope — survives all Activity lifecycle events
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: kotlinx.coroutines.Job? = null
    private var hostIp = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val username = intent?.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""

        // Cancel any previous startup and close old client connection cleanly
        connectJob?.cancel()
        LanChatApplication.repo?.disconnect()
        LanChatApplication.clearRepo()

        // Post notification immediately (required before 5 seconds)
        startForeground(NOTIFICATION_ID, buildNotification("Starting...", "Booting server"))

        connectJob = serviceScope.launch {
            startServerAndConnect(username, password)
        }

        return START_STICKY // restart if killed by OS
    }

    private suspend fun startServerAndConnect(username: String, password: String) {
        try {
            // ── Step 1: Start embedded server (skip if already running) ──
            if (!EmbeddedServer.isRunning()) {
                Log.i(TAG, "Starting embedded server...")
                EmbeddedServer.start()

                var waited = 0
                while (!EmbeddedServer.isRunning() && waited < 50) {
                    delay(100)
                    waited++
                }

                if (!EmbeddedServer.isRunning()) {
                    val err = EmbeddedServer.getLastError().ifBlank { "Server failed to start" }
                    Log.e(TAG, "Server not running after 5s: $err")
                    broadcastError(err)
                    stopSelf()
                    return
                }
            } else {
                Log.i(TAG, "Embedded server already running — reconnecting client")
            }

            // ── Step 2: Detect IP ──────────────────────────────────────
            hostIp = EmbeddedServer.localIp
            Log.i(TAG, "Server running. IP: $hostIp")
            updateNotification(hostIp, "Server online — connecting client...")

            // Start UDP discovery beacon so JOIN phones can find us
            DiscoveryBeacon.start(hostIp, EmbeddedServer.port)

            // ── Step 3: Connect local client to loopback ───────────────
            val downloadDir = Paths.get(
                getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            ).resolve("Downloads")

            // IMPORTANT: connect to 127.0.0.1 loopback — not the LAN IP
            // This ensures the client always reaches its own server
            val repo = ChatRepository("127.0.0.1", 9090, downloadDir)
            LanChatApplication.setRepo(repo)

            // Tell MessageSender our real LAN IP so voice calls work
            // (HOST connects via 127.0.0.1 loopback, so remoteIp on server = 127.0.0.1)
            MessageSender.MY_LAN_IP = hostIp
            Log.i(TAG, "Connecting client to loopback... (real IP: $hostIp)")
            repo.connect() // this is now a suspend fun running in serviceScope

            // ── Step 4: Login ──────────────────────────────────────────
            Log.i(TAG, "Connected. Logging in as $username...")
            repo.login(username, password)

            val authResult = repo.authState
                .first { it !is ChatRepository.AuthState.Idle }

            when (authResult) {
                is ChatRepository.AuthState.LoggedIn -> {
                    Log.i(TAG, "Logged in as ${authResult.username}")
                    updateNotification(hostIp, "Hosting • Logged in as ${authResult.username}")
                    broadcastReady(hostIp, authResult.username)
                }
                is ChatRepository.AuthState.Failed -> {
                    // Auto-register on first run
                    Log.i(TAG, "Login failed (${authResult.reason}), trying register...")
                    repo.register(username, password)
                    val regResult = repo.authState
                        .first { it !is ChatRepository.AuthState.Idle }
                    if (regResult is ChatRepository.AuthState.LoggedIn) {
                        Log.i(TAG, "Registered and logged in as ${regResult.username}")
                        updateNotification(hostIp, "Hosting • ${regResult.username}")
                        broadcastReady(hostIp, regResult.username)
                    } else {
                        broadcastError("Auth failed — check username/password")
                        stopSelf()
                    }
                }
                else -> {
                    broadcastError("Unexpected auth state")
                    stopSelf()
                }
            }

        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            Log.e(TAG, "ServerService error: $msg", e)
            broadcastError(msg)
            stopSelf()
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "ServerService destroyed — stopping server")
        connectJob?.cancel()
        connectJob = null
        serviceScope.cancel()
        LanChatApplication.repo?.disconnect()
        DiscoveryBeacon.stop()
        EmbeddedServer.stop()
        LanChatApplication.clearRepo()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Broadcasts to Activities ──────────────────────────────────────

    private fun broadcastReady(ip: String, username: String) {
        sendBroadcast(Intent(ACTION_READY).apply {
            putExtra(EXTRA_HOST_IP, ip)
            putExtra(EXTRA_USERNAME, username)
            setPackage(packageName)
        })
    }

    private fun broadcastError(msg: String) {
        updateNotification("Error", msg)
        sendBroadcast(Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MSG, msg)
            setPackage(packageName)
        })
    }

    // ── Notification ──────────────────────────────────────────────────

    private fun updateNotification(ip: String, status: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(ip, status))
    }

    private fun buildNotification(ip: String, status: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).apply { action = "STOP" },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notification)
            .setContentTitle("LAN Chat — HOSTING  •  $ip")
            .setContentText(status)
            .setContentIntent(tapIntent)
            .addAction(0, "[ STOP SERVER ]", stopIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "LAN Chat Server", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Keeps the LAN Chat server running" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
