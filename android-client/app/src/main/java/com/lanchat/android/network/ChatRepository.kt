package com.lanchat.android.network

import com.fasterxml.jackson.databind.JsonNode
import com.lanchat.client.ConnectionManager
import com.lanchat.client.FileReceiver
import com.lanchat.client.FileSender
import com.lanchat.client.MessageListener
import com.lanchat.client.MessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single source of truth for all network state.
 *
 * Used by ViewModels via dependency injection (or direct singleton access).
 * Wraps the Java client classes (ConnectionManager, MessageSender, MessageListener)
 * with Kotlin coroutines and StateFlow/SharedFlow for reactive UI.
 *
 * Lifecycle:
 *   connect()     → opens TCP socket, starts listener thread
 *   disconnect()  → closes socket cleanly
 *
 * All heavy I/O runs on Dispatchers.IO so the UI thread is never blocked.
 */
class ChatRepository(
    private val serverHost: String,
    private val serverPort: Int = 9090,
    private val downloadDir: Path
) : MessageListener.MessageCallback {

    // ── Java client layer ─────────────────────────────────────────────
    private val connection = ConnectionManager(serverHost, serverPort)
    val sender             = MessageSender(connection)
    val fileReceiver       = FileReceiver(downloadDir)

    private val listenerThread = Thread(
        MessageListener(connection, this), "msg-listener"
    ).apply { isDaemon = true }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Connection state ──────────────────────────────────────────────
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    // ── Auth state ────────────────────────────────────────────────────
    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    // ── Messages (SharedFlow = hot stream, replayed to new collectors) ─
    private val _messages = MutableSharedFlow<ChatEvent>(replay = 50)
    val messages: SharedFlow<ChatEvent> = _messages.asSharedFlow()

    // ── Online users ──────────────────────────────────────────────────
    private val _onlineUsers = MutableStateFlow<List<String>>(emptyList())
    val onlineUsers: StateFlow<List<String>> = _onlineUsers.asStateFlow()

    // ── Group membership ──────────────────────────────────────────────
    private val _myGroups = MutableStateFlow<List<String>>(emptyList())
    val myGroups: StateFlow<List<String>> = _myGroups.asStateFlow()

    // ── Pending file accepts (transferId → future) ────────────────────
    private val pendingFileAccepts = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    // ── Connect ───────────────────────────────────────────────────────

    suspend fun connect() = withContext(Dispatchers.IO) {
        connection.connect(true)
        _connected.value = true
        if (!listenerThread.isAlive) listenerThread.start()
    }

    fun disconnect() {
        connection.disconnect()
        _connected.value = false
        _authState.value = AuthState.Idle
    }

    // ── Auth ──────────────────────────────────────────────────────────

    fun login(username: String, password: String) {
        _authState.value = AuthState.Idle  // reset so first() waits for fresh response
        sender.sendLogin(username, password)
    }

    fun register(username: String, password: String) {
        _authState.value = AuthState.Idle  // reset so first() waits for fresh response
        sender.sendRegister(username, password)
    }

    // ── MessageCallback implementation ────────────────────────────────

    override fun onAuthSuccess(username: String, token: String) {
        _authState.value = AuthState.LoggedIn(username, token)
        emit(ChatEvent.SystemMessage("Logged in as $username"))
    }

    override fun onAuthFailure(reason: String) {
        _authState.value = AuthState.Failed(reason)
    }

    override fun onBroadcast(from: String, text: String, timestamp: Long) {
        emit(ChatEvent.Broadcast(from, text, timestamp))
    }

    override fun onPrivateMessage(from: String, text: String) {
        emit(ChatEvent.PrivateMessage(from, text))
    }

    override fun onGroupMessage(from: String, groupName: String, text: String) {
        emit(ChatEvent.GroupMessage(from, groupName, text))
    }

    override fun onUserJoined(username: String) {
        _onlineUsers.value = (_onlineUsers.value + username).distinct()
        emit(ChatEvent.SystemMessage("$username joined"))
    }

    override fun onUserLeft(username: String) {
        _onlineUsers.value = _onlineUsers.value - username
        emit(ChatEvent.SystemMessage("$username left"))
    }

    override fun onUserList(usersArray: JsonNode) {
        val list = mutableListOf<String>()
        usersArray.forEach { list.add(it.asText()) }
        _onlineUsers.value = list
    }

    override fun onGroupEvent(action: String, groupName: String) {
        when (action) {
            "CREATED", "JOINED", "MEMBER_OF" -> {
                if (!_myGroups.value.contains(groupName))
                    _myGroups.value = _myGroups.value + groupName
            }
            "LEFT" -> _myGroups.value = _myGroups.value - groupName
        }
        emit(ChatEvent.SystemMessage("Group $action: $groupName"))
    }

    override fun onFileOffer(from: String, fileName: String, fileSize: Long, transferId: String) {
        fileReceiver.onOffer(transferId, fileName, from)
        emit(ChatEvent.FileOffer(from, fileName, fileSize, transferId))
    }

    override fun onFileAccept(transferId: String) {
        pendingFileAccepts.remove(transferId)?.complete(Unit)
        emit(ChatEvent.FileAccepted(transferId))
    }

    override fun onFileChunk(transferId: String, chunkIndex: Int, base64Data: String) {
        fileReceiver.onChunk(transferId, chunkIndex, base64Data)
    }

    override fun onFileDone(transferId: String) {
        scope.launch(Dispatchers.IO) {
            val path = fileReceiver.onDone(transferId, null)
            if (path != null) emit(ChatEvent.FileReceived(path.toString()))
        }
    }

    override fun onVoiceCallOffer(from: String, callId: String, callerIp: String) {
        emit(ChatEvent.VoiceCallOffer(from, callId, callerIp))
    }

    override fun onVoiceCallAccept(from: String, callId: String, udpPort: Int, calleeIp: String) {
        emit(ChatEvent.VoiceCallAccepted(from, callId, udpPort, calleeIp))
    }

    override fun onVoiceCallReject(from: String, callId: String) {
        emit(ChatEvent.VoiceCallRejected(from, callId))
    }

    override fun onVoiceCallHangup(callId: String) {
        emit(ChatEvent.VoiceCallEnded(callId))
    }

    override fun onVoiceCallBusy(callId: String, reason: String) {
        emit(ChatEvent.SystemMessage("Call busy: $reason"))
    }

    override fun onError(code: String, message: String) {
        // If an error arrives while waiting for auth response, unblock the first{} in services
        if (_authState.value is AuthState.Idle) {
            _authState.value = AuthState.Failed(message)
        }
        emit(ChatEvent.Error(code, message))
    }

    override fun onHistoryReceived(
        channel: String,
        messages: List<com.lanchat.client.MessageListener.HistoryMessage>
    ) {
        emit(ChatEvent.HistoryLoaded(channel, messages))
    }

    override fun onDisconnected() {
        _connected.value = false
        emit(ChatEvent.SystemMessage("Disconnected. Reconnecting..."))
    }

    override fun onReconnected() {
        _connected.value = true
        emit(ChatEvent.SystemMessage("Reconnected. Please log in again."))
        _authState.value = AuthState.Idle
    }

    // ── File send ─────────────────────────────────────────────────────

    fun sendFile(targetUser: String, filePath: Path, onProgress: (Int) -> Unit) {
        scope.launch(Dispatchers.IO) {
            val fs = FileSender(sender, targetUser, filePath)
            val future = CompletableFuture<Unit>()
            pendingFileAccepts[fs.getTransferId()] = future
            try {
                fs.sendOffer()
                // Wait up to 30 seconds for the recipient to accept
                future.get(30, TimeUnit.SECONDS)
                fs.startSending { pct -> onProgress(pct) }
            } catch (e: java.util.concurrent.TimeoutException) {
                pendingFileAccepts.remove(fs.getTransferId())
                emit(ChatEvent.Error("FILE_TIMEOUT", "File offer not accepted within 30 seconds"))
            } catch (e: Exception) {
                pendingFileAccepts.remove(fs.getTransferId())
                emit(ChatEvent.Error("FILE_ERROR", e.message ?: "File send failed"))
            }
        }
    }

    // ── Internal emit helper ──────────────────────────────────────────

    private fun emit(event: ChatEvent) {
        scope.launch { _messages.emit(event) }
    }

    // ── Auth state sealed class ───────────────────────────────────────

    sealed class AuthState {
        object Idle : AuthState()
        data class LoggedIn(val username: String, val token: String) : AuthState()
        data class Failed(val reason: String) : AuthState()
    }

    // ── Chat event sealed class ───────────────────────────────────────

    sealed class ChatEvent {
        data class Broadcast(val from: String, val text: String, val timestamp: Long) : ChatEvent()
        data class PrivateMessage(val from: String, val text: String) : ChatEvent()
        data class GroupMessage(val from: String, val groupName: String, val text: String) : ChatEvent()
        data class SystemMessage(val text: String) : ChatEvent()
        data class Error(val code: String, val message: String) : ChatEvent()
        data class FileOffer(val from: String, val fileName: String, val fileSize: Long, val transferId: String) : ChatEvent()
        data class FileAccepted(val transferId: String) : ChatEvent()
        data class FileReceived(val localPath: String) : ChatEvent()
        data class VoiceCallOffer(val from: String, val callId: String, val callerIp: String) : ChatEvent()
        data class VoiceCallAccepted(val from: String, val callId: String, val udpPort: Int, val calleeIp: String) : ChatEvent()
        data class VoiceCallRejected(val from: String, val callId: String) : ChatEvent()
        data class VoiceCallEnded(val callId: String) : ChatEvent()
        /**
         * Fired when the server delivers persisted message history.
         * Already decrypted. Render these before live messages in the UI.
         */
        data class HistoryLoaded(
            val channel: String,
            val messages: List<com.lanchat.client.MessageListener.HistoryMessage>
        ) : ChatEvent()
    }
}
