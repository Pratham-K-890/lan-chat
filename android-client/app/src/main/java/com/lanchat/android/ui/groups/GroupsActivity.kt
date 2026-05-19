package com.lanchat.android.ui.groups

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lanchat.android.databinding.ActivityGroupsBinding
import com.lanchat.android.network.ChatRepository
import com.lanchat.android.ui.chat.ChatActivity
import com.lanchat.android.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

class GroupsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupsBinding
    private val vm: ChatViewModel by viewModels()
    private lateinit var adapter: GroupsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // repo is in LanChatApplication — ChatViewModel accesses it directly

        // Modern back press handling — replaces deprecated onBackPressed()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                startActivity(Intent(this@GroupsActivity, ChatActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
                finish()
            }
        })

        setupRecyclerView()
        setupButtons()
        observeEvents()
    }

    private fun setupRecyclerView() {
        adapter = GroupsAdapter { groupName ->
            AlertDialog.Builder(this)
                .setTitle("#$groupName")
                .setItems(arrayOf("> SEND_MESSAGE", "> LEAVE_GROUP")) { _, i ->
                    if (i == 0) promptGroupMessage(groupName)
                    else { vm.leaveGroup(groupName); toast("Left $groupName") }
                }.show()
        }
        binding.rvGroups.layoutManager = LinearLayoutManager(this)
        binding.rvGroups.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnCreateGroup.setOnClickListener {
            promptSingleInput("GROUP_NAME") { name ->
                if (name.isNotBlank()) {
                    vm.createGroup(name.uppercase().replace(" ", "-"))
                }
            }
        }

        binding.etGroupCommand.setOnEditorActionListener { _, _, _ ->
            val cmd = binding.etGroupCommand.text.toString().trim()
            if (cmd.isNotBlank()) {
                vm.joinGroup(cmd)
                binding.etGroupCommand.text?.clear()
                toast("Joining #$cmd...")
            }
            true
        }
    }

    private fun observeEvents() {
        // Keep the groups list in sync
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.myGroups.collect { groups -> adapter.setGroups(groups) }
            }
        }
        // Handle incoming events
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.messages.collect { event ->
                    when (event) {
                        is ChatRepository.ChatEvent.GroupMessage ->
                            toast("[#${event.groupName}] ${event.from}: ${event.text}")
                        is ChatRepository.ChatEvent.SystemMessage ->
                            if (event.text.contains("group", ignoreCase = true)) toast(event.text)
                        is ChatRepository.ChatEvent.Error -> toast(event.message)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun promptGroupMessage(groupName: String) {
        val et = android.widget.EditText(this).apply { hint = "MESSAGE"; setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this)
            .setView(et)
            .setPositiveButton("TRANSMIT") { _, _ ->
                val msg = et.text.toString().trim()
                if (msg.isNotBlank()) vm.sendGroupMessage(groupName, msg)
            }
            .setNegativeButton("ABORT", null)
            .show()
    }

    private fun promptSingleInput(hint: String, action: (String) -> Unit) {
        val et = android.widget.EditText(this).apply { this.hint = hint; setPadding(48, 24, 48, 24) }
        AlertDialog.Builder(this)
            .setView(et)
            .setPositiveButton("EXECUTE") { _, _ -> action(et.text.toString()) }
            .setNegativeButton("ABORT", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
