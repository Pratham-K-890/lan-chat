package com.lanchat.android.network

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
import com.lanchat.android.ui.chat.ChatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.nio.file.Paths

/**
 * Foreground Service for the JOIN (client-only) flow.
 *
 * Owns the TCP connection lifecycle independently of any Activity.
 * Uses SupervisorJob so it is NEVER cancelled by UI lifecycle events.
 */
class ChatService : Service() {

    companion object {
        private const val TAG             = "ChatService"
        const val CHANNEL_ID              = "lan_chat_channel"
        const val NOTIFICATION_ID         = 1001
        const val EXTRA_SERVER_IP         = "server_ip"
        const val EXTRA_USERNAME          = "username"
        const val EXTRA_PASSWORD          = "password"

        const val ACTION_CONNECTED        = "com.lanchat.CLIENT_CONNECTED"
        const val ACTION_ERROR            = "com.lanchat.CLIENT_ERROR"
        const val EXTRA_ERROR_MSG         = "error_msg"

        fun start(context: Context, serverIp: String, username: String, password: String) {
            val i = Intent(context, ChatService::class.java).apply {
                putExtra(EXTRA_SERVER_IP, serverIp)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ChatService::class.java))
        }
    }

    // Service-owned scope — lives as long as the service
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverIp = intent?.getStringExtra(EXTRA_SERVER_IP) ?: ""
        val username = intent?.getStringExtra(EXTRA_USERNAME) ?: ""
        val password = intent?.getStringExtra(EXTRA_PASSWORD) ?: ""

        // If restarted by OS with no extras, don't try to connect with empty credentials
        if (serverIp.isEmpty() || username.isEmpty() || password.isEmpty()) {
            android.util.Log.w("ChatService", "Started with empty credentials — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        // Cancel any previous attempt and close old connection so the server removes the old session
        connectJob?.cancel()
        LanChatApplication.repo?.disconnect()
        LanChatApplication.clearRepo()

        startForeground(NOTIFICATION_ID, buildNotification("Connecting to $serverIp..."))

        connectJob = serviceScope.launch {
            connectAndLogin(serverIp, username, password)
        }

        return START_NOT_STICKY  // don't auto-restart — LoginActivity will restart if needed
    }

    private suspend fun connectAndLogin(serverIp: String, username: String, password: String) {
        try {
            val downloadDir = Paths.get(
                getExternalFilesDir(null)?.absolutePath ?: filesDir.absolutePath
            ).resolve("Downloads")

            val repo = ChatRepository(serverIp, 9090, downloadDir)
            LanChatApplication.setRepo(repo)

            Log.i(TAG, "Connecting to $serverIp...")
            repo.connect()

            // Set our real LAN IP in MessageSender for UDP audio routing
            MessageSender.MY_LAN_IP = detectLocalIp()
            Log.i(TAG, "Connected. Logging in as $username... (myIp: ${MessageSender.MY_LAN_IP})")
            repo.login(username, password)

            val authResult = repo.authState
                .first { it !is ChatRepository.AuthState.Idle }

            when (authResult) {
                is ChatRepository.AuthState.LoggedIn -> {
                    Log.i(TAG, "Logged in as ${authResult.username}")
                    updateNotification("Connected as ${authResult.username}")
                    sendBroadcast(Intent(ACTION_CONNECTED).apply {
                        putExtra(EXTRA_USERNAME, authResult.username)
                        setPackage(packageName)
                    })
                }
                is ChatRepository.AuthState.Failed -> {
                    Log.i(TAG, "Login failed, trying register...")
                    repo.register(username, password)
                    val regResult = repo.authState
                        .first { it !is ChatRepository.AuthState.Idle }
                    if (regResult is ChatRepository.AuthState.LoggedIn) {
                        updateNotification("Connected as ${regResult.username}")
                        sendBroadcast(Intent(ACTION_CONNECTED).apply {
                            putExtra(EXTRA_USERNAME, regResult.username)
                            setPackage(packageName)
                        })
                    } else {
                        broadcastError("Auth failed — check credentials")
                        stopSelf()
                    }
                }
                else -> { broadcastError("Unexpected response"); stopSelf() }
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            Log.e(TAG, "ChatService error: $msg", e)
            broadcastError(msg)
            stopSelf()
        }
    }

    private fun detectLocalIp(): String {
        return try {
            java.net.NetworkInterface.getNetworkInterfaces()
                ?.toList()
                ?.flatMap { it.inetAddresses.toList() }
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull { a -> !a.isLoopbackAddress && a.hostAddress?.startsWith("169.254") != true }
                ?.hostAddress ?: ""
        } catch (_: Exception) { "" }
    }

    override fun onDestroy() {
        connectJob?.cancel()
        connectJob = null
        serviceScope.cancel()
        LanChatApplication.repo?.disconnect()
        LanChatApplication.clearRepo()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun broadcastError(msg: String) {
        updateNotification("Error: $msg")
        sendBroadcast(Intent(ACTION_ERROR).apply {
            putExtra(EXTRA_ERROR_MSG, msg)
            setPackage(packageName)
        })
    }

    private fun updateNotification(status: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun buildNotification(status: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, ChatActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat_notification)
            .setContentTitle("LAN Chat")
            .setContentText(status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "LAN Chat Connection", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Keeps the chat connection alive" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
}
