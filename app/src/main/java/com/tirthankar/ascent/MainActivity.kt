package com.tirthankar.ascent

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tirthankar.ascent.bridge.PendingImport
import com.tirthankar.ascent.bridge.ShareBridge
import com.tirthankar.ascent.designsystem.AscentTheme
import com.tirthankar.ascent.designsystem.SystemBackground
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var pendingImport: PendingImport

    /** Set when a Claude reply is shared into ASCENT; the shell then opens the Console. */
    private var openConsole by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            AscentTheme {
                SystemBackground(Modifier.fillMaxSize()) {
                    AscentApp(openConsole = openConsole, onConsoleOpened = { openConsole = false })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        val text = ShareBridge.sharedText(intent) ?: return
        pendingImport.offer(text)
        openConsole = true
    }
}
