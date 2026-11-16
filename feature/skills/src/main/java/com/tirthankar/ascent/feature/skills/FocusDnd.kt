package com.tirthankar.ascent.feature.skills

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.tirthankar.ascent.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Do Not Disturb for Focus Sessions (PHASE4.md section 9). Used only when the player has
 * granted notification-policy access; sessions work the same without it. The filter in
 * place before the session is saved, so it comes back even after a restart.
 */
@Singleton
class FocusDnd @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    private val manager: NotificationManager? get() = context.getSystemService(NotificationManager::class.java)

    fun hasAccess(): Boolean = manager?.isNotificationPolicyAccessGranted == true

    /** The system screen where the player grants (or refuses) access. */
    fun accessSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    suspend fun on() {
        val nm = manager ?: return
        if (!hasAccess()) return
        if (settings.focusDndPrevious() == null) settings.setFocusDndPrevious(nm.currentInterruptionFilter)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
    }

    suspend fun off() {
        val previous = settings.focusDndPrevious() ?: return
        if (hasAccess()) manager?.setInterruptionFilter(previous)
        settings.setFocusDndPrevious(null)
    }
}
