package com.lanchat.android.ui.login

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lanchat.android.databinding.ActivityLoginBinding
import com.lanchat.android.network.ChatService
import com.lanchat.android.server.DiscoveryScanner
import com.lanchat.android.server.ServerService
import com.lanchat.android.ui.chat.ChatActivity
import com.lanchat.android.viewmodel.LoginViewModel
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val vm: LoginViewModel by viewModels()

    // ── Broadcast receivers — get results from Services ───────────────

    private val serverReadyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ip       = intent.getStringExtra(ServerService.EXTRA_HOST_IP) ?: ""
            val username = intent.getStringExtra(ServerService.EXTRA_USERNAME) ?: ""
            vm.onHostReady(ip, username)
            navigateToChat(username, hostIp = ip)
        }
    }

    private val serverErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(ServerService.EXTRA_ERROR_MSG) ?: "Unknown error"
            vm.onHostError(msg)
        }
    }

    private val clientConnectedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val username = intent.getStringExtra(ChatService.EXTRA_USERNAME) ?: ""
            vm.onJoinLoggedIn(username)
            navigateToChat(username)
        }
    }

    private val clientErrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val msg = intent.getStringExtra(ChatService.EXTRA_ERROR_MSG) ?: "Connection failed"
            vm.onJoinError(msg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.includeAuthHost.etUsername.setText(vm.savedUsername)
        binding.includeAuthJoin.etUsername.setText(vm.savedUsername)

        registerReceivers()
        setupCredentialForm()
        setupModeButtons()
        setupHostPanel()
        setupJoinPanel()
        observeState()
        observeDiscovery()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (vm.uiState.value) {
                    is LoginViewModel.UiState.NeedLogin,
                    is LoginViewModel.UiState.ModeSelect -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                    else -> vm.goToModeSelect()
                }
            }
        })
    }

    override fun onDestroy() {
        unregisterReceivers()
        super.onDestroy()
    }

    private fun registerReceivers() {
        registerReceiver(serverReadyReceiver,
            IntentFilter(ServerService.ACTION_READY), RECEIVER_NOT_EXPORTED)
        registerReceiver(serverErrorReceiver,
            IntentFilter(ServerService.ACTION_ERROR), RECEIVER_NOT_EXPORTED)
        registerReceiver(clientConnectedReceiver,
            IntentFilter(ChatService.ACTION_CONNECTED), RECEIVER_NOT_EXPORTED)
        registerReceiver(clientErrorReceiver,
            IntentFilter(ChatService.ACTION_ERROR), RECEIVER_NOT_EXPORTED)
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(serverReadyReceiver)   } catch (_: Exception) {}
        try { unregisterReceiver(serverErrorReceiver)   } catch (_: Exception) {}
        try { unregisterReceiver(clientConnectedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(clientErrorReceiver)   } catch (_: Exception) {}
    }

    // ── Credential form (first launch only) ───────────────────────────

    private fun setupCredentialForm() {
        listOf(binding.includeAuthHost, binding.includeAuthJoin).forEach { form ->
            form.btnLogin.setOnClickListener {
                val u = form.etUsername.text.toString().trim()
                val p = form.etPassword.text.toString()
                if (!validateAuth(u, p)) return@setOnClickListener
                vm.saveCredentialsAndContinue(u, p)
            }
            form.btnRegister.setOnClickListener {
                val u = form.etUsername.text.toString().trim()
                val p = form.etPassword.text.toString()
                if (!validateAuth(u, p)) return@setOnClickListener
                vm.saveCredentialsAndContinue(u, p)
            }
        }
    }

    // ── HOST / JOIN buttons ───────────────────────────────────────────

    private fun setupModeButtons() {
        binding.btnHost.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("[ HOST_SESSION ]")
                .setMessage(
                    "Your phone will run the chat server.\n\n" +
                    "OTHER phones must connect to YOUR hotspot WiFi.\n\n" +
                    "Tap OPEN HOTSPOT SETTINGS to enable it first,\n" +
                    "or tap START NOW if hotspot is already on."
                )
                .setPositiveButton("OPEN HOTSPOT SETTINGS") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                    toast("Enable hotspot, then come back and tap HOST again")
                }
                .setNegativeButton("START NOW") { _, _ ->
                    startServer()
                }
                .setNeutralButton("CANCEL", null)
                .show()
        }

        binding.btnJoin.setOnClickListener {
            showJoinDialog()
        }
    }

    private fun startServer() {
        // Stop any active client session before (re)starting the server
        ChatService.stop(this)
        vm.onHostStarting()
        ServerService.start(this, vm.savedUsername, vm.savedPassword)
    }

    private fun showJoinDialog() {
        AlertDialog.Builder(this)
            .setTitle("[ JOIN_SESSION ]")
            .setMessage("Connect this phone to the HOST's WiFi hotspot first, then choose:")
            .setPositiveButton("AUTO-SCAN") { _, _ ->
                ensureWifiThenDo {
                    vm.onJoinStartScanning()
                }
            }
            .setNeutralButton("ENTER IP") { _, _ ->
                ensureWifiThenDo {
                    vm.onJoinStartScanning() // show join panel with manual IP field
                }
            }
            .setNegativeButton("OPEN WIFI SETTINGS") { _, _ ->
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                toast("Connect to host hotspot WiFi, then tap JOIN again")
            }
            .show()
    }

    private fun ensureWifiThenDo(action: () -> Unit) {
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        if (!wm.isWifiEnabled) {
            AlertDialog.Builder(this)
                .setTitle("[ WIFI_OFF ]")
                .setMessage("Connect to the host's hotspot first.")
                .setPositiveButton("OPEN WIFI SETTINGS") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                .setNegativeButton("TRY ANYWAY") { _, _ -> action() }
                .show()
        } else {
            action()
        }
    }

    // ── HOST panel ────────────────────────────────────────────────────

    private fun setupHostPanel() {
        binding.btnStopHost.setOnClickListener {
            ServerService.stop(this)
            vm.onHostStopped()
        }
    }

    // ── JOIN panel ────────────────────────────────────────────────────

    private fun setupJoinPanel() {
        binding.btnConnectFound.setOnClickListener {
            val ip = binding.tvFoundIp.text.toString().trim()
            if (ip.isNotEmpty()) connectToIp(ip)
        }
        binding.btnConnectManual.setOnClickListener {
            val ip = binding.etServerIp.text.toString().trim()
            when {
                ip.isEmpty()   -> toast("Enter the host IP address")
                !isValidIp(ip) -> toast("Invalid IP — e.g. 192.168.43.1")
                else           -> connectToIp(ip)
            }
        }
        binding.btnBackFromJoin.setOnClickListener {
            vm.stopJoining()
        }
    }

    private fun connectToIp(ip: String) {
        // Stop any active server or client session before connecting as a joiner
        ServerService.stop(this)
        vm.onJoinConnecting()
        ChatService.start(this, ip, vm.savedUsername, vm.savedPassword)
    }

    // ── Auto-discovery observer ───────────────────────────────────────

    private fun observeDiscovery() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DiscoveryScanner.discovered.collect { host ->
                    if (host != null && vm.uiState.value is LoginViewModel.UiState.JoinScanning) {
                        vm.onJoinFoundHost(host.ip)
                        binding.tvFoundIp.text = host.ip
                        binding.panelFoundHost.visibility = View.VISIBLE
                        binding.etServerIp.setText(host.ip)
                    }
                }
            }
        }
    }

    // ── State observer ────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { renderState(it) }
            }
        }
    }

    private fun renderState(s: LoginViewModel.UiState) {
        // Reset all panels
        binding.panelModeSelect.visibility  = View.GONE
        binding.panelHostReady.visibility   = View.GONE
        binding.panelJoining.visibility     = View.GONE
        binding.progressServer.visibility   = View.GONE
        binding.progressScan.visibility     = View.GONE
        binding.includeAuthHost.root.visibility = View.GONE
        binding.btnStopHost.visibility      = View.GONE

        when (s) {
            is LoginViewModel.UiState.NeedLogin -> {
                binding.panelHostReady.visibility           = View.VISIBLE
                binding.includeAuthHost.root.visibility     = View.VISIBLE
                binding.tvServerStatus.text                 = "[ WELCOME ]"
                binding.tvHostIp.text                       = "Enter your username & password"
                binding.tvTopStatus.text                    = "[ FIRST_LAUNCH ]"
            }
            is LoginViewModel.UiState.ModeSelect -> {
                binding.panelModeSelect.visibility = View.VISIBLE
                binding.tvTopStatus.text           = "[ SELECT_MODE ]"
            }
            is LoginViewModel.UiState.HostStarting -> {
                binding.panelHostReady.visibility   = View.VISIBLE
                binding.btnStopHost.visibility      = View.VISIBLE
                binding.progressServer.visibility   = View.VISIBLE
                binding.tvServerStatus.text         = "[ STARTING SERVER... ]"
                binding.tvHostIp.text               = "Please wait..."
                binding.tvTopStatus.text            = "[ BOOTING ]"
            }
            is LoginViewModel.UiState.HostReady -> {
                binding.panelHostReady.visibility   = View.VISIBLE
                binding.btnStopHost.visibility      = View.VISIBLE
                binding.tvServerStatus.text         = "[ SERVER ONLINE ]"
                binding.tvHostIp.text               = s.ip.ifEmpty { "192.168.43.1" }
                binding.tvTopStatus.text            = "[ HOSTING — Share this IP ]"
                toast("Server ready! Share IP: ${s.ip}")
            }
            is LoginViewModel.UiState.JoinScanning -> {
                binding.panelJoining.visibility         = View.VISIBLE
                binding.progressScan.visibility         = View.VISIBLE
                binding.panelFoundHost.visibility       = View.GONE
                binding.includeAuthJoin.root.visibility = View.GONE
                binding.tvTopStatus.text                = "[ SCANNING... ]"
            }
            is LoginViewModel.UiState.JoinFound -> {
                binding.panelJoining.visibility     = View.VISIBLE
                binding.panelFoundHost.visibility   = View.VISIBLE
                binding.progressScan.visibility     = View.GONE
                binding.tvFoundIp.text              = s.ip
                binding.tvTopStatus.text            = "[ HOST FOUND: ${s.ip} ]"
            }
            is LoginViewModel.UiState.Connecting -> {
                binding.panelJoining.visibility     = View.VISIBLE
                binding.progressScan.visibility     = View.VISIBLE
                binding.tvTopStatus.text            = "[ CONNECTING... ]"
            }
            is LoginViewModel.UiState.LoggedIn -> {
                // Already navigated via broadcast receiver — nothing to do
            }
            is LoginViewModel.UiState.Error -> {
                binding.panelModeSelect.visibility = View.VISIBLE
                binding.tvTopStatus.text           = "[ ERROR ]"
                AlertDialog.Builder(this)
                    .setTitle("[ ERROR ]")
                    .setMessage(s.message)
                    .setPositiveButton("OK") { _, _ -> vm.goToModeSelect() }
                    .show()
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────

    private fun navigateToChat(username: String, hostIp: String = "") {
        startActivity(Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_USERNAME, username)
            if (hostIp.isNotEmpty()) putExtra(ChatActivity.EXTRA_HOST_IP, hostIp)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        })
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private fun validateAuth(u: String, p: String) = when {
        u.length < 3 -> { toast("Username: min 3 chars"); false }
        p.length < 6 -> { toast("Password: min 6 chars"); false }
        else -> true
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all { (it.toIntOrNull() ?: return false) in 0..255 }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
