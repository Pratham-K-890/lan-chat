package com.lanchat.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lanchat.android.LanChatApplication
import com.lanchat.android.network.ChatRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * ChatViewModel — wraps the app-level ChatRepository.
 *
 * CRITICAL: Every send call goes through Dispatchers.IO to avoid
 * NetworkOnMainThreadException. Never call socket methods on the main thread.
 */
class ChatViewModel : ViewModel() {

    private val repo: ChatRepository
        get() = LanChatApplication.repo
            ?: throw IllegalStateException("Not connected")

    val messages: SharedFlow<ChatRepository.ChatEvent>
        get() = repo.messages

    val onlineUsers: StateFlow<List<String>>
        get() = repo.onlineUsers

    val myGroups: StateFlow<List<String>>
        get() = repo.myGroups

    val connected: StateFlow<Boolean>
        get() = repo.connected

    // ── All sends dispatched to IO thread ─────────────────────────────

    private fun io(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try { block() } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "Send error: ${e.message}", e)
            }
        }
    }

    // ── Messaging ─────────────────────────────────────────────────────
    fun sendBroadcast(text: String)                   = io { repo.sender.sendBroadcast(text) }
    fun sendPrivateMessage(to: String, text: String)  = io { repo.sender.sendPrivateMessage(to, text) }
    fun sendGroupMessage(group: String, text: String) = io { repo.sender.sendGroupMessage(group, text) }

    // ── Groups ────────────────────────────────────────────────────────
    fun createGroup(name: String) = io { repo.sender.sendCreateGroup(name) }
    fun joinGroup(name: String)   = io { repo.sender.sendJoinGroup(name) }
    fun leaveGroup(name: String)  = io { repo.sender.sendLeaveGroup(name) }

    // ── Voice calls ───────────────────────────────────────────────────
    fun callUser(targetUsername: String, callId: String) =
        io { repo.sender.sendVoiceCallOffer(targetUsername, callId) }

    fun acceptCall(callerUsername: String, callId: String, udpPort: Int) =
        io { repo.sender.sendVoiceCallAccept(callerUsername, callId, udpPort) }

    fun rejectCall(fromUsername: String, callId: String) =
        io { repo.sender.sendVoiceCallReject(fromUsername, callId) }

    fun hangup(callId: String) =
        io { repo.sender.sendVoiceCallHangup(callId) }

    // ── File transfer ─────────────────────────────────────────────────
    fun acceptFile(transferId: String) =
        io { repo.sender.sendFileAccept(transferId) }

    fun sendFile(targetUser: String, filePath: Path, onProgress: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repo.sendFile(targetUser, filePath, onProgress)
            } catch (e: Exception) {
                android.util.Log.e("ChatViewModel", "File send error: ${e.message}", e)
            }
        }
    }

    // ── History ───────────────────────────────────────────────────────
    fun requestBroadcastHistory() =
        io { repo.sender.requestBroadcastHistory() }

    fun requestGroupHistory(groupName: String) =
        io { repo.sender.requestGroupHistory(groupName, 50) }

    // ── Disconnect ────────────────────────────────────────────────────
    fun disconnect() = LanChatApplication.clearRepo()
}
