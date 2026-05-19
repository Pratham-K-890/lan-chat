package com.lanchat.android.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.lanchat.android.LanChatApplication
import com.lanchat.android.network.ChatRepository
import com.lanchat.android.server.DiscoveryScanner
import com.lanchat.android.server.EmbeddedServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LoginViewModel — UI state only, NO network/server logic.
 *
 * All connection logic is in ServerService (HOST) and ChatService (JOIN).
 * Those services own their own CoroutineScope (SupervisorJob) so they
 * are NEVER cancelled by Activity/ViewModel lifecycle events.
 *
 * This ViewModel only:
 *   - Tracks which UI panel is visible
 *   - Saves/loads credentials from SharedPreferences
 *   - Exposes discovery scanner results
 */
class LoginViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        const val PREFS_NAME    = "lanchat_prefs"
        const val KEY_USERNAME  = "saved_username"
        const val KEY_PASSWORD  = "saved_password"
        const val KEY_LOGGED_IN = "auto_login_enabled"
    }

    enum class Mode { NONE, HOST, JOIN }

    sealed class UiState {
        object NeedLogin    : UiState()   // first launch — show credential form
        object ModeSelect   : UiState()   // credentials saved — show HOST/JOIN
        object HostStarting : UiState()   // waiting for ServerService to boot
        data class HostReady(val ip: String, val username: String) : UiState()
        object JoinScanning : UiState()
        data class JoinFound(val ip: String) : UiState()
        object Connecting   : UiState()
        data class LoggedIn(val username: String) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow<UiState>(
        if (hasCredentials()) UiState.ModeSelect else UiState.NeedLogin
    )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    var mode: Mode = Mode.NONE
        private set

    val discoveredHost = DiscoveryScanner.discovered

    // ── Credentials ───────────────────────────────────────────────────

    var savedUsername: String
        get()      = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var savedPassword: String
        get()      = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    private fun hasCredentials() =
        prefs.getBoolean(KEY_LOGGED_IN, false)
        && savedUsername.isNotBlank()
        && savedPassword.isNotBlank()

    fun saveCredentialsAndContinue(username: String, password: String) {
        savedUsername = username
        savedPassword = password
        prefs.edit().putBoolean(KEY_LOGGED_IN, true).apply()
        _uiState.value = UiState.ModeSelect
    }

    fun logout() {
        prefs.edit().putBoolean(KEY_LOGGED_IN, false).apply()
        savedPassword = ""
        mode = Mode.NONE
        LanChatApplication.clearRepo()
        _uiState.value = UiState.NeedLogin
    }

    // ── HOST UI state (actual work done in ServerService) ─────────────

    fun onHostStarting() {
        mode = Mode.HOST
        _uiState.value = UiState.HostStarting
    }

    fun onHostReady(ip: String, username: String) {
        _uiState.value = UiState.HostReady(ip, username)
    }

    fun onHostError(msg: String) {
        mode = Mode.NONE
        _uiState.value = UiState.Error(msg)
    }

    fun onHostStopped() {
        mode = Mode.NONE
        _uiState.value = UiState.ModeSelect
    }

    // ── JOIN UI state (actual work done in ChatService) ───────────────

    fun onJoinStartScanning() {
        mode = Mode.JOIN
        _uiState.value = UiState.JoinScanning
        DiscoveryScanner.start()
    }

    fun onJoinFoundHost(ip: String) {
        _uiState.value = UiState.JoinFound(ip)
        prefs.edit().putString("server_host", ip).apply()
    }

    fun onJoinConnecting() {
        _uiState.value = UiState.Connecting
        DiscoveryScanner.stop()
    }

    fun onJoinLoggedIn(username: String) {
        _uiState.value = UiState.LoggedIn(username)
    }

    fun onJoinError(msg: String) {
        mode = Mode.NONE
        _uiState.value = UiState.Error(msg)
    }

    fun stopJoining() {
        DiscoveryScanner.stop()
        mode = Mode.NONE
        _uiState.value = UiState.ModeSelect
    }

    fun goToModeSelect() {
        DiscoveryScanner.stop()
        mode = Mode.NONE
        _uiState.value = UiState.ModeSelect
    }

    // Expose the app-level repo for ChatActivity/ChatViewModel
    val repo: ChatRepository? get() = LanChatApplication.repo
}
