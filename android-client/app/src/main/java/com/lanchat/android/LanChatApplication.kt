package com.lanchat.android

import android.app.Application
import com.lanchat.android.network.ChatRepository
import java.nio.file.Paths

/**
 * Application-level singleton that owns the ChatRepository (TCP connection).
 *
 * WHY HERE and not in a ViewModel:
 *   ViewModels are scoped to Activities/Fragments and get destroyed on navigation.
 *   The TCP socket MUST survive screen changes, so it lives in the Application
 *   which exists for the entire process lifetime.
 *
 * Server lifecycle is owned by ServerService (a ForegroundService) — completely
 * independent of any Activity or ViewModel.
 */
class LanChatApplication : Application() {

    companion object {
        // Global repo — set once after successful connect, cleared on logout
        var repo: ChatRepository? = null
            private set

        fun setRepo(r: ChatRepository) { repo = r }

        fun clearRepo() {
            repo?.disconnect()
            repo = null
        }

        fun isConnected() = repo != null
    }

    override fun onCreate() {
        super.onCreate()
    }
}
