package com.lanchat.android.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lanchat.android.databinding.ActivityChatBinding
import com.lanchat.android.network.ChatRepository
import com.lanchat.android.ui.groups.GroupsActivity
import com.lanchat.android.ui.voice.VoiceCallActivity
import com.lanchat.android.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import java.nio.file.Paths
import java.util.UUID

class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_USERNAME = "extra_username"
        const val EXTRA_HOST_IP  = "extra_host_ip"
    }

    private lateinit var binding: ActivityChatBinding
    private val vm: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter
    private var username: String = ""

    // Track active call dialog so we can dismiss it on reject
    private var activeCallDialog: AlertDialog? = null
    private var pendingCallId: String = ""
    private var pendingFileTarget: String = ""

    // Guard against SharedFlow(replay=50) re-delivering one-shot events on rotation
    private val handledCallIds     = mutableSetOf<String>()
    private val handledTransferIds = mutableSetOf<String>()

    // ── Permission launchers ──────────────────────────────────────────

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { sendPickedFile(it) }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) showCallUserDialog()
        else toast("Microphone permission required for voice calls")
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        username = intent.getStringExtra(EXTRA_USERNAME) ?: "USER"

        setupRecyclerView()
        setupInput()
        setupBottomNav()
        setupHostIpBanner()
        observeEvents()

        binding.tvSysTime.text = "[ SYS_TIME: ${
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
        } ]"

        try { vm.requestBroadcastHistory() } catch (_: Exception) {}
    }

    // ── RecyclerView ──────────────────────────────────────────────────

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter(username)
        binding.rvMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@ChatActivity).apply { stackFromEnd = true }
        }
    }

    // ── Host IP banner ────────────────────────────────────────────────

    private fun setupHostIpBanner() {
        val hostIp = intent.getStringExtra(EXTRA_HOST_IP) ?: ""
        if (hostIp.isEmpty()) return
        binding.tvHostIpBanner.visibility = View.VISIBLE
        binding.tvHostIpBanner.text = "[ HOST IP: $hostIp ]  ·  tap to copy"
        binding.tvHostIpBanner.setOnClickListener {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Host IP", hostIp))
            toast("Copied: $hostIp")
        }
    }

    // ── Message input — runs send on IO via ViewModel ─────────────────

    private fun setupInput() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            binding.etMessage.text?.clear()

            // Show own message IMMEDIATELY — don't wait for server echo
            messageAdapter.addMessage(
                MessageAdapter.ChatMessage(username, text, System.currentTimeMillis()))
            scrollToBottom()

            // Send to server on IO thread
            vm.sendBroadcast(text)
        }
        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            binding.btnSend.performClick(); true
        }
    }

    private fun scrollToBottom() {
        binding.rvMessages.post {
            val count = messageAdapter.itemCount
            if (count > 0) binding.rvMessages.scrollToPosition(count - 1)
        }
    }

    // ── Bottom navigation ─────────────────────────────────────────────

    private fun setupBottomNav() {
        binding.navDm.setOnClickListener  { showUserActionDialog() }
        binding.navGrp.setOnClickListener { startActivity(Intent(this, GroupsActivity::class.java)) }
        binding.navVox.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) showCallUserDialog()
            else micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ── User action panel ─────────────────────────────────────────────

    private fun showUserActionDialog() {
        val users = vm.onlineUsers.value.filter { it != username }
        if (users.isEmpty()) { toast("No other users online yet"); return }

        AlertDialog.Builder(this)
            .setTitle("Online Users")
            .setItems(users.map { "● $it" }.toTypedArray()) { _, i ->
                showActionsForUser(users[i])
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun showActionsForUser(targetUser: String) {
        val actions = arrayOf(
            "Send Direct Message",
            "Voice Call",
            "Send File"
        )
        AlertDialog.Builder(this)
            .setTitle(targetUser)
            .setItems(actions) { _, i ->
                when (i) {
                    0 -> showDmDialog(targetUser)
                    1 -> initiateVoiceCall(targetUser)
                    2 -> { pendingFileTarget = targetUser; filePicker.launch("*/*") }
                }
            }
            .setNegativeButton("Back") { _, _ -> showUserActionDialog() }
            .show()
    }

    private fun showDmDialog(targetUser: String) {
        val et = EditText(this).apply { hint = "Message to $targetUser"; setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this)
            .setTitle("DM to $targetUser")
            .setView(et)
            .setPositiveButton("Send") { _, _ ->
                val msg = et.text.toString().trim()
                if (msg.isNotEmpty()) {
                    // Show immediately
                    messageAdapter.addMessage(
                        MessageAdapter.ChatMessage("[DM to $targetUser]", msg, System.currentTimeMillis()))
                    scrollToBottom()
                    vm.sendPrivateMessage(targetUser, msg)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Voice call (initiator side) ───────────────────────────────────

    private fun showCallUserDialog() {
        val users = vm.onlineUsers.value.filter { it != username }
        if (users.isEmpty()) { toast("No other users online"); return }

        AlertDialog.Builder(this)
            .setTitle("Voice Call")
            .setItems(users.map { "Call $it" }.toTypedArray()) { _, i ->
                initiateVoiceCall(users[i])
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun initiateVoiceCall(targetUser: String) {
        val callId = UUID.randomUUID().toString()
        pendingCallId = callId
        vm.callUser(targetUser, callId)
        toast("Calling $targetUser...")
    }

    // ── File send ─────────────────────────────────────────────────────

    private fun sendPickedFile(uri: Uri) {
        val target = pendingFileTarget.ifEmpty { return }
        pendingFileTarget = ""

        val cr = contentResolver
        val name = cr.query(uri, null, null, null, null)?.use { c ->
            val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            c.moveToFirst(); c.getString(col)
        } ?: "file_${System.currentTimeMillis()}"

        val tmp = Paths.get(cacheDir.absolutePath, name)
        cr.openInputStream(uri)?.use { input ->
            java.nio.file.Files.copy(input, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }

        binding.progressSend.visibility = View.VISIBLE
        binding.progressSend.progress   = 0
        toast("Sending $name to $target...")

        vm.sendFile(target, tmp) { pct ->
            runOnUiThread {
                binding.progressSend.progress = pct
                if (pct >= 100) { binding.progressSend.visibility = View.GONE; toast("File sent!") }
            }
        }
    }

    // ── Event handler ─────────────────────────────────────────────────

    private fun observeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.messages.collect { handleEvent(it) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.onlineUsers.collect { users ->
                    binding.tvOnlineUsers.text =
                        if (users.isEmpty()) "No users online"
                        else users.joinToString("  |  ") { u ->
                            if (u == username) "[ YOU: $u ]" else "● $u"
                        }
                }
            }
        }
    }

    private fun handleEvent(event: ChatRepository.ChatEvent) {
        when (event) {
            is ChatRepository.ChatEvent.Broadcast -> {
                // Skip own broadcast — already shown optimistically in setupInput
                if (event.from != username) {
                    messageAdapter.addMessage(
                        MessageAdapter.ChatMessage(event.from, event.text, event.timestamp))
                }
            }

            is ChatRepository.ChatEvent.PrivateMessage -> {
                // Only show incoming DMs (own DMs already shown optimistically)
                if (event.from != username) {
                    messageAdapter.addMessage(
                        MessageAdapter.ChatMessage("[DM from ${event.from}]", event.text))
                }
            }

            is ChatRepository.ChatEvent.GroupMessage ->
                messageAdapter.addMessage(MessageAdapter.ChatMessage("[${event.groupName}] ${event.from}", event.text))

            is ChatRepository.ChatEvent.SystemMessage ->
                messageAdapter.addMessage(MessageAdapter.ChatMessage("SYSTEM", event.text))

            is ChatRepository.ChatEvent.Error ->
                messageAdapter.addMessage(MessageAdapter.ChatMessage("ERROR", event.message))

            is ChatRepository.ChatEvent.HistoryLoaded ->
                if (event.messages.isNotEmpty()) messageAdapter.prependHistory(event.channel, event.messages)

            is ChatRepository.ChatEvent.FileOffer -> {
                if (handledTransferIds.add(event.transferId)) showFileOfferDialog(event)
            }

            is ChatRepository.ChatEvent.FileReceived -> toast("File saved: ${event.localPath}")

            // Incoming call — show dialog, store reference so we can dismiss on reject
            is ChatRepository.ChatEvent.VoiceCallOffer -> {
                if (handledCallIds.add(event.callId)) showIncomingCallDialog(event)
            }

            // Caller receives acceptance — open VoiceCallActivity
            is ChatRepository.ChatEvent.VoiceCallAccepted -> {
                pendingCallId = ""
                // Initiator listens on 9091, sends to receiver's port 9092
                startVoiceCall(event.calleeIp, 9092, event.callId, isInitiator = true)
            }

            // Callee rejected — dismiss dialog, DO NOT open VoiceCallActivity
            is ChatRepository.ChatEvent.VoiceCallRejected -> {
                activeCallDialog?.dismiss()
                activeCallDialog = null
                pendingCallId    = ""
                toast("${event.from} declined the call")
            }

            is ChatRepository.ChatEvent.VoiceCallEnded -> {
                activeCallDialog?.dismiss()
                activeCallDialog = null
                pendingCallId    = ""
                toast("Call ended")
            }

            else -> {}
        }
        scrollToBottom()
    }

    // ── File offer dialog ─────────────────────────────────────────────

    private fun showFileOfferDialog(event: ChatRepository.ChatEvent.FileOffer) {
        AlertDialog.Builder(this)
            .setTitle("Incoming File")
            .setMessage("From: ${event.from}\nFile: ${event.fileName}\nSize: ${event.fileSize / 1024} KB")
            .setPositiveButton("Accept") { _, _ -> vm.acceptFile(event.transferId); toast("Receiving...") }
            .setNegativeButton("Decline", null)
            .show()
    }

    // ── Incoming call dialog ──────────────────────────────────────────

    private fun showIncomingCallDialog(event: ChatRepository.ChatEvent.VoiceCallOffer) {
        // Dismiss any previous call dialog
        activeCallDialog?.dismiss()
        pendingCallId = event.callId

        activeCallDialog = AlertDialog.Builder(this)
            .setTitle("Incoming Call")
            .setMessage("${event.from} is calling you")
            .setPositiveButton("Answer") { _, _ ->
                vm.acceptCall(event.from, event.callId, 9092)
                // Receiver listens on 9092, sends to initiator's port 9091
                startVoiceCall(event.callerIp, 9091, event.callId, isInitiator = false)
                activeCallDialog = null; pendingCallId = ""
            }
            .setNegativeButton("Decline") { _, _ ->
                vm.rejectCall(event.from, event.callId)
                activeCallDialog = null; pendingCallId = ""
            }
            .setCancelable(false)
            .show()
    }

    private fun startVoiceCall(peerIp: String, peerPort: Int, callId: String, isInitiator: Boolean) {
        startActivity(Intent(this, VoiceCallActivity::class.java).apply {
            putExtra(VoiceCallActivity.EXTRA_PEER_IP,      peerIp)
            putExtra(VoiceCallActivity.EXTRA_PEER_PORT,    peerPort)
            putExtra(VoiceCallActivity.EXTRA_CALL_ID,      callId)
            putExtra(VoiceCallActivity.EXTRA_IS_INITIATOR, isInitiator)
        })
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
