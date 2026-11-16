package com.tirthankar.ascent.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The Android side of the bridge: share sheet out, clipboard in. No network, ever. */
object ShareBridge {

    /** A share sheet for [text]; pick Claude, or anything else. */
    fun shareIntent(text: String, chooserTitle: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text),
            chooserTitle,
        )

    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java)
            ?.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    /** The clipboard's text. Android only allows this while ASCENT is in the foreground. */
    fun readClipboard(context: Context): String? {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    /** The text of an incoming ACTION_SEND share, if it is one. */
    fun sharedText(intent: Intent?): String? =
        intent?.takeIf { it.action == Intent.ACTION_SEND && it.type?.startsWith("text/") == true }
            ?.getStringExtra(Intent.EXTRA_TEXT)
            ?.takeIf { it.isNotBlank() }
}

/** Text shared into ASCENT, waiting for the Console to review it. */
@Singleton
class PendingImport @Inject constructor() {
    private val _text = MutableStateFlow<String?>(null)
    val text: StateFlow<String?> = _text.asStateFlow()

    fun offer(text: String) {
        _text.value = text
    }

    fun consume(): String? = _text.value.also { _text.value = null }
}
